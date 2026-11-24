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
    private final java.util.WeakHashMap<ImageView,Integer> requests=new java.util.WeakHashMap<>();
    private int sequence;

    BoxArt(BoxHttp http) {
        this.http = http;
        workers.allowCoreThreadTimeOut(true);
        frosting.allowCoreThreadTimeOut(true);
    }

    void load(ImageView view, String url) { load(view,url,640); }
    void load(ImageView view, String url, int edge) { load(view,url,edge,null); }
    void load(ImageView view, String url, int edge, Ready ready) {
        String cacheKey=edge+"\n"+url;
        if (view.getBackground() == null && !(view instanceof BoxBackdrop)) {
            view.setBackgroundDrawable(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{0xff2a4050, 0xff101d2a}));
        }
        view.setTag(url);
        int request=++sequence;requests.put(view,request);
        Bitmap existing = url.isEmpty() ? null : cache.get(cacheKey);
        Bitmap preview=existing;
        if(preview==null&&edge>640&&!url.isEmpty())preview=cached(url);
        view.setImageBitmap(preview);
        if(preview!=null&&ready!=null)ready.image(preview);
        if (url.isEmpty() || existing != null || closed) return;
        workers.execute(() -> {
            if (closed) return;
            Bitmap bitmap = cache.get(cacheKey);
            if (bitmap == null) bitmap = fetch(url, edge);
            if (bitmap == null || closed) return;
            cache.put(cacheKey, bitmap);
            Bitmap result = bitmap;
            main.post(() -> {
                if (!closed && url.equals(view.getTag()) && Integer.valueOf(request).equals(requests.get(view))) {
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
            float scale=Math.min(1f,192f/Math.max(source.getWidth(),source.getHeight()));
            int width=Math.max(1,Math.round(source.getWidth()*scale)),height=Math.max(1,Math.round(source.getHeight()*scale));
            Bitmap sample=Bitmap.createScaledBitmap(source,width,height,true);
            int[] pixels=new int[width*height],scratch=new int[pixels.length];
            sample.getPixels(pixels,0,width,0,0,width,height);
            if(sample!=source)sample.recycle();
            for(int pass=0;pass<4;pass++){
                for(int axis=0;axis<2;axis++){
                    int lines=axis==0?height:width,length=axis==0?width:height;
                    for(int line=0;line<lines;line++){
                        int red=0,green=0,blue=0;
                        for(int k=-6;k<=6;k++){
                            int at=Math.max(0,Math.min(length-1,k));int color=pixels[axis==0?line*width+at:at*width+line];
                            red+=(color>>16)&255;green+=(color>>8)&255;blue+=color&255;
                        }
                        for(int at=0;at<length;at++){
                            scratch[axis==0?line*width+at:at*width+line]=0xff000000|((red/13)<<16)|((green/13)<<8)|(blue/13);
                            int previous=Math.max(0,at-6),next=Math.min(length-1,at+7);
                            int remove=pixels[axis==0?line*width+previous:previous*width+line],add=pixels[axis==0?line*width+next:next*width+line];
                            red+=((add>>16)&255)-((remove>>16)&255);green+=((add>>8)&255)-((remove>>8)&255);blue+=(add&255)-(remove&255);
                        }
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
                long pixels=Math.min(2000000,Runtime.getRuntime().maxMemory()/64);
                while ((long)(options.outWidth/options.inSampleSize)*(options.outHeight/options.inSampleSize)>pixels
                        || Math.max(options.outWidth,options.outHeight)/options.inSampleSize>edge*2f) {
                    options.inSampleSize *= 2;
                }
                options.inJustDecodeBounds = false;
                options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                Bitmap decoded=BitmapFactory.decodeByteArray(data,0,data.length,options);
                if(decoded==null)return null;
                int longest=Math.max(decoded.getWidth(),decoded.getHeight());
                if(longest<=edge)return decoded;
                float scale=(float)edge/longest;
                Bitmap result=Bitmap.createScaledBitmap(decoded,Math.max(1,Math.round(decoded.getWidth()*scale)),Math.max(1,Math.round(decoded.getHeight()*scale)),true);
                if(result!=decoded)decoded.recycle();return result;
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
        requests.clear();
    }
}
