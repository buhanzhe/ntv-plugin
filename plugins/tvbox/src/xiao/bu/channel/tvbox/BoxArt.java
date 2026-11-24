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
    interface Ready { void image(Bitmap bitmap); }
    private static final int DOWNLOAD_LIMIT = 3 * 1024 * 1024;
    private final BoxHttp http;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 15, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), new ThreadPoolExecutor.DiscardOldestPolicy());
    private final ThreadPoolExecutor frosting = new ThreadPoolExecutor(1, 1, 15, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.DiscardOldestPolicy());
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
        frosting.allowCoreThreadTimeOut(true);
    }

    void load(ImageView view, String url) { load(view,url,640); }
    void load(ImageView view, String url, int edge) { load(view,url,edge,null); }
    void load(ImageView view, String url, int edge, Ready ready) {
        String cacheKey=edge+"\n"+url;
        if (view.getBackground() == null) {
            view.setBackgroundDrawable(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{0xff2a4050, 0xff101d2a}));
        }
        view.setTag(url);
        Bitmap existing = url.isEmpty() ? null : cache.get(cacheKey);
        if(existing==null&&edge>640&&!url.isEmpty())existing=cached(url);
        view.setImageBitmap(existing);
        if(existing!=null&&ready!=null)ready.image(existing);
        if (url.isEmpty() || existing != null || closed) return;
        workers.execute(() -> {
            if (closed) return;
            Bitmap bitmap = cache.get(cacheKey);
            if (bitmap == null) bitmap = fetch(url, edge);
            if (bitmap == null || closed) return;
            cache.put(cacheKey, bitmap);
            Bitmap result = bitmap;
            main.post(() -> {
                if (!closed && url.equals(view.getTag())) {
                    view.setImageBitmap(result);
                    if(ready!=null)ready.image(result);
                }
            });
        });
    }

    Bitmap cached(String url) { return cache.get("640\n"+url); }

    void frost(Bitmap source, Ready ready) {
        if(closed)return;
        frosting.execute(() -> {
            if(closed)return;
            float scale=Math.min(1f,96f/Math.max(source.getWidth(),source.getHeight()));
            int width=Math.max(1,Math.round(source.getWidth()*scale)),height=Math.max(1,Math.round(source.getHeight()*scale));
            Bitmap sample=Bitmap.createScaledBitmap(source,width,height,true);
            int[] pixels=new int[width*height],scratch=new int[pixels.length];
            sample.getPixels(pixels,0,width,0,0,width,height);
            if(sample!=source)sample.recycle();
            for(int pass=0;pass<4;pass++){
                for(int axis=0;axis<2;axis++){
                    for(int y=0;y<height;y++)for(int x=0;x<width;x++){
                        int red=0,green=0,blue=0;
                        for(int k=-3;k<=3;k++){
                            int index=axis==0?y*width+Math.max(0,Math.min(width-1,x+k)):Math.max(0,Math.min(height-1,y+k))*width+x;
                            int color=pixels[index];red+=(color>>16)&255;green+=(color>>8)&255;blue+=color&255;
                        }
                        scratch[y*width+x]=0xff000000|((red/7)<<16)|((green/7)<<8)|(blue/7);
                    }
                    int[] swap=pixels;pixels=scratch;scratch=swap;
                }
            }
            Bitmap result=Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888);
            main.post(() -> {if(!closed)ready.image(result);});
        });
    }

    private Bitmap fetch(String url, int edge) {
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
                while (Math.max(options.outWidth, options.outHeight) / (options.inSampleSize * 2) >= edge) {
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
        frosting.shutdownNow();
        main.removeCallbacksAndMessages(null);
        cache.evictAll();
    }
}
