package xiao.bu.channel.tvbox;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.widget.ImageView;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.lang.ref.WeakReference;
import org.json.JSONObject;
import okhttp3.Response;

/** Bounded artwork cache for recycled TVBox cards. */
final class BoxArt {
    interface Ready { void image(Bitmap bitmap); }
    private static final int DOWNLOAD_LIMIT = 3 * 1024 * 1024;
    private final BoxHttp http;
    private final int thumbnailEdge;
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
    private final WeakHashMap<ImageView, Target> requests = new WeakHashMap<>();
    private final Map<String, ArtworkRequest> inFlight = new HashMap<>();
    private static final class Target {
        final WeakReference<ImageView> view;
        final String key;
        final Ready ready;
        ArtworkRequest request;

        Target(ImageView view, String key, Ready ready) {
            this.view = new WeakReference<>(view);
            this.key = key;
            this.ready = ready;
        }
    }

    private final class ArtworkRequest implements Runnable {
        final String key;
        final List<String> urls;
        final int edge;
        final boolean wide;
        final List<Target> targets = new ArrayList<>();
        volatile boolean abandoned;

        ArtworkRequest(String key, List<String> urls, int edge, boolean wide) {
            this.key = key;
            this.urls = urls;
            this.edge = edge;
            this.wide = wide;
        }

        @Override public void run() {
            if (closed || abandoned) return;
            Bitmap bitmap = cache.get(key);
            if (bitmap == null) bitmap = wide ? fetchWide() : fetch(urls.get(0), edge);
            if (closed || abandoned) return;
            if (bitmap != null) cache.put(key, bitmap);
            Bitmap result = bitmap;
            main.post(() -> {
                if (inFlight.get(key) == this) inFlight.remove(key);
                if (closed) return;
                for (Target target : targets) {
                    target.request = null;
                    ImageView image = target.view.get();
                    if (result != null && image != null && requests.get(image) == target) {
                        image.setImageBitmap(result);
                        if (target.ready != null) target.ready.image(result);
                    }
                }
                targets.clear();
            });
        }

        private Bitmap fetchWide() {
            Bitmap fallback = null;
            for (String url : urls) {
                if (abandoned || closed) break;
                Bitmap bitmap = fetch(url, edge);
                if (bitmap == null) continue;
                if (!usableBackdrop(bitmap)) {
                    bitmap.recycle();
                } else if (bitmap.getWidth() >= bitmap.getHeight() * 1.25f) {
                    if (fallback != null) fallback.recycle();
                    return bitmap;
                } else if (fallback == null) {
                    fallback = bitmap;
                } else {
                    bitmap.recycle();
                }
            }
            return fallback;
        }
    }

    BoxArt(BoxHttp http, int thumbnailEdge) {
        this.http = http.forArtwork();
        this.thumbnailEdge = thumbnailEdge;
        workers.allowCoreThreadTimeOut(true);
        frosting.allowCoreThreadTimeOut(true);
    }

    int thumbnailEdge() { return thumbnailEdge; }
    void load(ImageView view, String url) { load(view,url,thumbnailEdge); }
    void load(ImageView view, String url, int edge) { load(view,url,edge,null); }
    void load(ImageView view, String url, int edge, Ready ready) {
        load(view,Collections.singletonList(url),edge,false,ready,0);
    }

    void loadBackdrop(ImageView view, List<String> urls, int edge, Ready ready) {
        load(view,urls,edge,true,ready,0);
    }

    private static boolean usableBackdrop(Bitmap bitmap) {
        return Math.min(bitmap.getWidth(), bitmap.getHeight()) >= 96;
    }

    private void load(ImageView view,List<String> urls,int edge,boolean wide,Ready ready,int retry) {
        String url = urls.isEmpty() ? "" : urls.get(0);
        String cacheKey = edge + "\n" + (wide ? "wide\n" : "") + TextUtils.join("\n", urls);
        Target previous = requests.get(view);
        if (previous != null && previous.key.equals(cacheKey) && previous.ready == null && ready == null
                && (previous.request != null || view.getDrawable() != null)) return;
        if (previous != null && previous.request != null) {
            ArtworkRequest old = previous.request;
            old.targets.remove(previous);
            if (!old.key.equals(cacheKey) && old.targets.isEmpty()) {
                if (workers.remove(old) || old.wide) {
                    old.abandoned = true;
                    inFlight.remove(old.key);
                }
            }
        }
        Target target = new Target(view, cacheKey, ready);
        requests.put(view, target);
        if (view.getBackground() == null && !(view instanceof BoxBackdrop)) {
            view.setBackgroundDrawable(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{0xff2a4050, 0xff101d2a}));
        }
        view.setTag(url);
        Bitmap existing = url.isEmpty() ? null : cache.get(cacheKey);
        Bitmap preview=existing;
        if (!wide && preview == null && edge > thumbnailEdge && !url.isEmpty()) preview = cached(url);
        if (preview != null || view.getDrawable() != null) view.setImageBitmap(preview);
        if(preview!=null&&ready!=null)ready.image(preview);
        if (url.isEmpty() || existing != null || closed) return;
        ArtworkRequest running = inFlight.get(cacheKey);
        if (running != null) {
            running.targets.add(target);
            target.request = running;
            return;
        }
        ArtworkRequest request = new ArtworkRequest(cacheKey, urls, edge, wide);
        request.targets.add(target);
        target.request = request;
        inFlight.put(cacheKey, request);
        try {
            workers.execute(request);
        } catch (java.util.concurrent.RejectedExecutionException full) {
            inFlight.remove(cacheKey);
            target.request = null;
            if (retry < 3) main.postDelayed(() -> {
                ImageView image = target.view.get();
                if (!closed && image != null && requests.get(image) == target) {
                    load(image, urls, edge, wide, ready, retry + 1);
                }
            }, 500L * (retry + 1));
        }
    }

    Bitmap cached(String url) { return cache.get(thumbnailEdge + "\n" + url); }

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
