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
            new ArrayBlockingQueue<>(64), new ThreadPoolExecutor.AbortPolicy());
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
    private final java.util.Map<String,java.util.List<Target>> inFlight=new java.util.HashMap<>();
    private static final class Target {
        final java.lang.ref.WeakReference<ImageView> view;final int request;final String url;final Ready ready;
        Target(ImageView view,int request,String url,Ready ready){this.view=new java.lang.ref.WeakReference<>(view);this.request=request;this.url=url;this.ready=ready;}
    }

    BoxArt(BoxHttp http) {
        this.http = http.forArtwork();
        workers.allowCoreThreadTimeOut(true);
        frosting.allowCoreThreadTimeOut(true);
    }

    void load(ImageView view, String url) { load(view,url,640); }
    void load(ImageView view, String url, int edge) { load(view,url,edge,null); }
    void load(ImageView view, String url, int edge, Ready ready) {
        load(view,url,edge,ready,0);
    }
    private void load(ImageView view,String url,int edge,Ready ready,int retry) {
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
        Target target=new Target(view,request,url,ready);
        java.util.List<Target> waiting=inFlight.get(cacheKey);
        if(waiting!=null){waiting.add(target);return;}
        waiting=new java.util.ArrayList<>();waiting.add(target);inFlight.put(cacheKey,waiting);
        try{workers.execute(() -> {
            if (closed) return;
            Bitmap bitmap = cache.get(cacheKey);
            if (bitmap == null) bitmap = fetch(url, edge);
            if(closed)return;
            if(bitmap!=null)cache.put(cacheKey, bitmap);
            Bitmap result = bitmap;
            main.post(() -> {
                java.util.List<Target> targets=inFlight.remove(cacheKey);if(closed||targets==null||result==null)return;
                for(Target item:targets){ImageView image=item.view.get();
                    if(image!=null&&item.url.equals(image.getTag())&&Integer.valueOf(item.request).equals(requests.get(image))){
                        image.setImageBitmap(result);if(item.ready!=null)item.ready.image(result);
                    }
                }
            });
        });}catch(java.util.concurrent.RejectedExecutionException full){
            java.util.List<Target> targets=inFlight.remove(cacheKey);
            if(retry<3&&targets!=null)main.postDelayed(()->{for(Target item:targets){ImageView image=item.view.get();if(!closed&&image!=null&&item.url.equals(image.getTag())&&Integer.valueOf(item.request).equals(requests.get(image)))load(image,item.url,edge,item.ready,retry+1);}},500L*(retry+1));
        }
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
            JSONObject headers = headers(url);
            if(!hasHeader(headers,"User-Agent"))headers.put("User-Agent","Mozilla/5.0");
            if(!hasHeader(headers,"Accept"))headers.put("Accept","image/jpeg,image/png,image/webp,image/gif,*/*;q=0.5");
            if(BoxHttp.url(address).host().endsWith(".doubanio.com")&&!hasHeader(headers,"Referer"))headers.put("Referer","https://movie.douban.com/");
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
        return BoxImageHeaders.offset(url);
    }
    private static boolean hasHeader(JSONObject headers,String name){java.util.Iterator<String> keys=headers.keys();while(keys.hasNext())if(keys.next().equalsIgnoreCase(name))return true;return false;}
    static JSONObject headers(String url)throws Exception{
        return BoxImageHeaders.parse(url);
    }

    void close() {
        closed = true;
        workers.shutdownNow();
        http.cancelRequests();
        frosting.shutdownNow();
        main.removeCallbacksAndMessages(null);
        cache.evictAll();
        requests.clear();
        inFlight.clear();
    }
}
