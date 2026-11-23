package xiao.bu.channel.tvbox;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.Collections;
import org.json.JSONObject;
import okhttp3.Response;

/** Bounded artwork cache for recycled TVBox cards. */
final class BoxArt {
    private static final int DOWNLOAD_LIMIT = 3 * 1024 * 1024;
    private final BoxHttp http;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 15, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), new ThreadPoolExecutor.DiscardOldestPolicy());
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(
            (int) Math.min(6 * 1024 * 1024, Runtime.getRuntime().maxMemory() / 10)) {
        @Override protected int sizeOf(String url, Bitmap bitmap) {
            return bitmap.getRowBytes() * bitmap.getHeight();
        }
    };
    private volatile boolean closed;

    BoxArt(BoxHttp http) {
        this.http = http;
        workers.allowCoreThreadTimeOut(true);
    }

    void load(ImageView view, String url) {
        if (view.getBackground() == null) {
            view.setBackgroundDrawable(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{0xff4e3b5c, 0xff29232e}));
        }
        view.setTag(url);
        Bitmap existing = url.isEmpty() ? null : cache.get(url);
        view.setImageBitmap(existing);
        if (url.isEmpty() || existing != null || closed) return;
        workers.execute(() -> {
            if (closed) return;
            Bitmap bitmap = cache.get(url);
            if (bitmap == null) bitmap = fetch(url);
            if (bitmap == null || closed) return;
            cache.put(url, bitmap);
            Bitmap result = bitmap;
            main.post(() -> {
                if (!closed && url.equals(view.getTag())) view.setImageBitmap(result);
            });
        });
    }

    private Bitmap fetch(String url) {
        try {
            int offset = headerOffset(url);
            String address = offset < 0 ? url : url.substring(0, offset);
            JSONObject headers = new JSONObject();
            if (offset >= 0) {
                String suffix = android.net.Uri.decode(url.substring(offset + 1));
                if (suffix.startsWith("{")) headers = new JSONObject(suffix);
                else for (String field : url.substring(offset + 1).split("&")) {
                    int equal = field.indexOf('=');
                    if (equal <= 0) continue;
                    String name = java.net.URLDecoder.decode(field.substring(0, equal), "UTF-8");
                    String value = java.net.URLDecoder.decode(field.substring(equal + 1), "UTF-8");
                    if (name.matches("[A-Za-z0-9!#$%&'*+.^_`|~-]+") && value.indexOf('\r') < 0 && value.indexOf('\n') < 0)
                        headers.put(name, value);
                }
            }
            try (Response response = http.openMedia(BoxHttp.url(address), headers, Collections.emptyMap())) {
                if (!response.isSuccessful()) return null;
                byte[] data = BoxHttp.read(response.body(), DOWNLOAD_LIMIT);
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, options);
                if (options.outWidth <= 0 || options.outHeight <= 0) return null;
                options.inSampleSize = 1;
                while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 640) {
                    options.inSampleSize *= 2;
                }
                options.inJustDecodeBounds = false;
                options.inPreferredConfig = Bitmap.Config.RGB_565;
                return BitmapFactory.decodeByteArray(data, 0, data.length, options);
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    static int headerOffset(String url) {
        int pipe = url.indexOf('|');
        if (pipe >= 0) return pipe;
        java.util.regex.Matcher marker = java.util.regex.Pattern.compile("(?i)@(?=(?:Referer|User-Agent|Cookie|Origin|Authorization|Accept)=|\\{|%7b)").matcher(url);
        return marker.find() ? marker.start() : -1;
    }

    void close() {
        closed = true;
        workers.shutdownNow();
        main.removeCallbacksAndMessages(null);
        cache.evictAll();
    }
}
