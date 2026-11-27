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
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.lang.ref.WeakReference;
import org.json.JSONObject;
import okhttp3.Call;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Bounded artwork cache for recycled TVBox cards. */
final class BoxArt {
    interface Ready { void image(Bitmap bitmap); }
    private static final int DOWNLOAD_LIMIT = 3 * 1024 * 1024;
    private static final long FAILED_RETRY_MS = 12000L;
    private final android.content.Context context;
    private final ClassLoader assets;
    private final BoxHttp http;
    private final int thumbnailEdge;
    private final boolean constrained;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor workers;
    private final ThreadPoolExecutor frosting = new ThreadPoolExecutor(1, 1, 15, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), background("tvbox-frost"), new ThreadPoolExecutor.DiscardOldestPolicy());
    private final LruCache<String, Bitmap> thumbnails;
    private final LruCache<String, Bitmap> backdrops;
    private final LruCache<String, Long> failed = new LruCache<>(64);
    private volatile boolean closed;
    private final WeakHashMap<ImageView, Target> requests = new WeakHashMap<>();
    private final Map<String, ArtworkRequest> inFlight = new HashMap<>();
    private volatile int frostGeneration;
    private static final class Target {
        final WeakReference<ImageView> view;
        final String key;
        Ready ready;
        ArtworkRequest request;
        boolean retryScheduled;

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
        private volatile Call active;

        ArtworkRequest(String key, List<String> urls, int edge, boolean wide) {
            this.key = key;
            this.urls = urls;
            this.edge = edge;
            this.wide = wide;
        }

        @Override public void run() {
            if (closed || abandoned) return;
            LruCache<String, Bitmap> cache = wide ? backdrops : thumbnails;
            Bitmap bitmap = cache.get(key);
            if (bitmap == null) bitmap = wide ? fetchWide() : fetch(urls.get(0), edge, this);
            if (closed || abandoned) return;
            if (bitmap != null) cache.put(key, bitmap);
            else failed.put(key, android.os.SystemClock.uptimeMillis());
            Bitmap result = bitmap;
            main.post(() -> {
                if (inFlight.get(key) == this) inFlight.remove(key);
                if (closed) return;
                for (Target target : targets) {
                    target.request = null;
                    Ready ready = target.ready;
                    target.ready = null;
                    ImageView image = target.view.get();
                    if (result != null && image != null && requests.get(image) == target) {
                        image.setImageBitmap(result);
                        if (ready != null) ready.image(result);
                    }
                }
                targets.clear();
            });
        }

        void cancel() {
            abandoned = true;
            Call call = active;
            if (call != null) call.cancel();
        }

        private Bitmap fetchWide() {
            Bitmap fallback = null;
            for (String url : urls) {
                if (abandoned || closed) break;
                Bitmap bitmap = fetch(url, edge, this);
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

    BoxArt(android.content.Context context, ClassLoader assets, BoxHttp http, int thumbnailEdge) {
        this.context = context.getApplicationContext();
        this.assets = assets;
        this.http = http.forArtwork();
        this.thumbnailEdge = thumbnailEdge;
        constrained = android.os.Build.VERSION.SDK_INT <= 19;
        int threads = constrained ? 1 : 2;
        workers = new ThreadPoolExecutor(threads, threads, 15, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(constrained ? 18 : 48), background("tvbox-art"), new ThreadPoolExecutor.AbortPolicy());
        long heap = Runtime.getRuntime().maxMemory();
        thumbnails = bitmapCache((int) Math.min((constrained ? 2 : 5) * 1024L * 1024L, heap / 12));
        backdrops = bitmapCache((int) Math.min((constrained ? 2 : 5) * 1024L * 1024L, heap / 14));
        workers.allowCoreThreadTimeOut(true);
        frosting.allowCoreThreadTimeOut(true);
    }

    private static ThreadFactory background(String name) {
        return work -> new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            work.run();
        }, name);
    }

    private static LruCache<String, Bitmap> bitmapCache(int bytes) {
        return new LruCache<String, Bitmap>(bytes) {
            @Override protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getRowBytes() * bitmap.getHeight();
            }
        };
    }

    int thumbnailEdge() { return thumbnailEdge; }
    int tileEdge(int width, boolean portrait) {
        // Quantize sizes so cards keep hitting the same cache during small layout changes.
        int pixels = portrait ? width * 3 / 2 : width;
        return pixels <= 0 ? thumbnailEdge : Math.min(thumbnailEdge, Math.max(128, (pixels + 63) / 64 * 64));
    }
    void load(ImageView view, String url) { load(view,url,thumbnailEdge); }
    void load(ImageView view, String url, int edge) { load(view,url,edge,null); }
    void load(ImageView view, String url, int edge, Ready ready) {
        load(view,Collections.singletonList(url),edge,false,ready,0,true);
    }

    void prepare(ImageView view, String url, int edge) {
        load(view, Collections.singletonList(url), edge, false, null, 0, false);
    }

    void clear(ImageView view) {
        cancelPending(view);
        requests.remove(view);
        view.setTag(null);
        if (view.getDrawable() != null) view.setImageDrawable(null);
    }

    void cancelPending(ImageView view) {
        Target target = requests.get(view);
        if (target == null) return;
        release(target, null);
        target.request = null;
        target.retryScheduled = false;
        target.ready = null;
    }

    void loadBackdrop(ImageView view, List<String> urls, int edge, Ready ready) {
        load(view,urls,edge,true,ready,0,true);
    }

    private static boolean usableBackdrop(Bitmap bitmap) {
        return Math.min(bitmap.getWidth(), bitmap.getHeight()) >= 96;
    }

    private void release(Target target, String nextKey) {
        if (target == null || target.request == null) return;
        ArtworkRequest old = target.request;
        old.targets.remove(target);
        if (!old.key.equals(nextKey) && old.targets.isEmpty()) {
            old.cancel();
            workers.remove(old);
            if (inFlight.get(old.key) == old) inFlight.remove(old.key);
        }
    }

    private void load(ImageView view,List<String> urls,int edge,boolean wide,Ready ready,int retry,boolean start) {
        String url = urls.isEmpty() ? "" : urls.get(0);
        String cacheKey = edge + "\n" + (wide ? "wide\n" : "") + TextUtils.join("\n", urls);
        Target previous = requests.get(view);
        if (previous != null && previous.key.equals(cacheKey) && previous.ready == null && ready == null
                && (previous.request != null || view.getDrawable() != null || !start)) return;
        release(previous, cacheKey);
        Target target = new Target(view, cacheKey, ready);
        requests.put(view, target);
        if (view.getBackground() == null && !(view instanceof BoxBackdrop)) {
            if (view instanceof BoxImage) {
                BoxImage image = (BoxImage) view;
                if (!image.hasPlaceholder()) image.setPlaceholder(placeholder());
            } else view.setBackgroundDrawable(placeholder());
        }
        view.setTag(url);
        Bitmap existing = url.isEmpty() ? null : (wide ? backdrops : thumbnails).get(cacheKey);
        Bitmap preview=existing;
        if (!wide && preview == null && edge > thumbnailEdge && !url.isEmpty()) preview = cached(url);
        if (preview != null) view.setImageBitmap(preview);
        else if (view.getDrawable() != null) view.setImageDrawable(null);
        Long failedAt = failed.get(cacheKey);
        boolean startRequest = start && !url.isEmpty() && existing == null && !closed
                && (failedAt == null || android.os.SystemClock.uptimeMillis() - failedAt >= FAILED_RETRY_MS);
        if (!startRequest) target.ready = null;
        if (preview != null && ready != null) ready.image(preview);
        if (!startRequest) return;
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
            if (retry >= (constrained ? 1 : 3)) {
                target.ready = null;
                return;
            }
            target.retryScheduled = true;
            main.postDelayed(() -> {
                ImageView image = target.view.get();
                if (closed || image == null || requests.get(image) != target || !target.retryScheduled) return;
                target.retryScheduled = false;
                Ready retryReady = target.ready;
                target.ready = null;
                load(image, urls, edge, wide, retryReady, retry + 1, true);
            }, 500L * (retry + 1));
        }
    }

    Bitmap cached(String url) { return thumbnails.get(thumbnailEdge + "\n" + url); }

    private static GradientDrawable placeholder() {
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xff2a4050, 0xff101d2a});
    }

    void frost(Bitmap source, Ready ready) {
        if(closed)return;
        int generation = ++frostGeneration;
        frosting.execute(() -> {
            if(closed || generation != frostGeneration)return;
            float scale=Math.min(1f,(constrained ? 128f : 192f)/Math.max(source.getWidth(),source.getHeight()));
            int width=Math.max(1,Math.round(source.getWidth()*scale)),height=Math.max(1,Math.round(source.getHeight()*scale));
            Bitmap sample=Bitmap.createScaledBitmap(source,width,height,true);
            int[] pixels=new int[width*height],scratch=new int[pixels.length];
            sample.getPixels(pixels,0,width,0,0,width,height);
            if(sample!=source)sample.recycle();
            for(int pass=0;pass<(constrained ? 3 : 4);pass++){
                if (closed || generation != frostGeneration) return;
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
            main.post(() -> {if(!closed && generation == frostGeneration)ready.image(result);});
        });
    }

    void cancelFrost() {
        frostGeneration++;
        frosting.getQueue().clear();
    }

    private Bitmap fetch(String url, int edge, ArtworkRequest request) {
        try {
            int offset = headerOffset(url);
            String address = offset < 0 ? url : url.substring(0, offset);
            JSONObject headers = headers(url);
            if(!hasHeader(headers,"User-Agent"))headers.put("User-Agent","Mozilla/5.0");
            if(!hasHeader(headers,"Accept"))headers.put("Accept","image/jpeg,image/png,image/webp,image/gif,*/*;q=0.5");
            okhttp3.HttpUrl imageUrl = BoxHttp.url(address);
            if(imageUrl.host().endsWith(".doubanio.com")&&!hasHeader(headers,"Referer"))headers.put("Referer","https://movie.douban.com/");
            Call call = http.mediaCall(imageUrl, headers, Collections.emptyMap());
            request.active = call;
            if (request.abandoned || closed) call.cancel();
            try (Response response = call.execute()) {
                if (!response.isSuccessful()) return null;
                ResponseBody body = response.body();
                if (body == null || body.contentLength() > DOWNLOAD_LIMIT) return null;
                okio.BufferedSource bytes = body.source();
                if (bytes.request(DOWNLOAD_LIMIT + 1L)) return null;
                if (request.abandoned || closed) return null;
                byte[] data = bytes.readByteArray();
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, options);
                if (options.outWidth <= 0 || options.outHeight <= 0)
                    return BoxHeif.decode(context, assets, data, edge);
                options.inSampleSize = 1;
                long pixels=Math.min(2000000,Runtime.getRuntime().maxMemory()/64);
                float target = edge * (constrained && !request.wide ? 1.55f : 2f);
                while ((long)(options.outWidth/options.inSampleSize)*(options.outHeight/options.inSampleSize)>pixels
                        || Math.max(options.outWidth,options.outHeight)/options.inSampleSize>target) {
                    options.inSampleSize *= 2;
                }
                options.inJustDecodeBounds = false;
                options.inPreferredConfig = constrained && !request.wide && edge <= 256
                        && "image/jpeg".equals(options.outMimeType) ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888;
                Bitmap decoded=BitmapFactory.decodeByteArray(data,0,data.length,options);
                if(decoded==null)return BoxHeif.decode(context, assets, data, edge);
                int longest=Math.max(decoded.getWidth(),decoded.getHeight());
                if(longest<=edge)return decoded;
                float scale=(float)edge/longest;
                Bitmap result=Bitmap.createScaledBitmap(decoded,Math.max(1,Math.round(decoded.getWidth()*scale)),Math.max(1,Math.round(decoded.getHeight()*scale)),true);
                if(result!=decoded)decoded.recycle();return result;
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            request.active = null;
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
        thumbnails.evictAll();
        backdrops.evictAll();
        failed.evictAll();
        requests.clear();
        inFlight.clear();
    }
}
