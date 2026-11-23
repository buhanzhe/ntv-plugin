package xiao.bu.channel.tvbox;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import java.io.Closeable;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** A bounded browser session for TVBox parse=1 and ordinary web parser pages. */
final class BoxSniffer implements Closeable {
    private final Context context;
    private final FrameLayout parent;
    private final BoxHttp http;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean closed;
    private WebView browser;
    private volatile CountDownLatch waiting;
    private volatile String userAgent = "";

    BoxSniffer(Context context, FrameLayout parent, BoxHttp http) {
        this.context = context; this.parent = parent; this.http = http;
    }

    JSONObject resolve(String url) throws Exception {
        BoxHttp.url(url);
        if (closed) throw new IOException("频道已关闭");
        if (android.os.Build.VERSION.SDK_INT < 21) {
            JSONObject direct = legacyDocument(url);
            if (direct != null) return direct;
        }
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<JSONObject> found = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        waiting = ready;
        main.post(() -> {
            if (closed || ready.getCount() == 0) { ready.countDown(); return; }
            try {
                if (android.os.Build.VERSION.SDK_INT < 21) android.webkit.CookieSyncManager.createInstance(context);
                browser = new WebView(context);
                browser.getSettings().setJavaScriptEnabled(true);
                browser.getSettings().setDomStorageEnabled(true);
                if (android.os.Build.VERSION.SDK_INT >= 17) browser.getSettings().setMediaPlaybackRequiresUserGesture(false);
                userAgent = browser.getSettings().getUserAgentString();
                LegacyClient client = android.os.Build.VERSION.SDK_INT >= 21
                        ? new ModernClient(url, ready, found) : new LegacyClient(url, ready, found);
                browser.setWebViewClient(client);
                browser.setAlpha(0.01f);
                parent.addView(browser, new FrameLayout.LayoutParams(2, 2));
                browser.onResume(); browser.resumeTimers();
                browser.loadUrl(url);
                if (android.os.Build.VERSION.SDK_INT >= 19) main.postDelayed(new Runnable() {
                    @Override public void run() {
                        if (closed || ready.getCount() == 0 || browser == null) return;
                        DomMedia.inspect(browser, client);
                        main.postDelayed(this, 500);
                    }
                }, 500);
            } catch (RuntimeException | LinkageError error) { failure.set(error); ready.countDown(); }
        });
        try {
            if (!ready.await(20, TimeUnit.SECONDS)) throw new IOException("网页未返回可播放线路，请切换线路");
            if (failure.get() != null) throw new IOException("设备网页内核无法解析此线路", failure.get());
            if (found.get() == null) throw new IOException("网页解析已取消");
            return found.get();
        } finally {
            ready.countDown();
            if (waiting == ready) waiting = null;
            main.post(this::clear);
        }
    }

    // Legacy WebView can stall before requesting media; ordinary HTML media tags need no JS.
    private JSONObject legacyDocument(String page) {
        try (okhttp3.Response response = http.probeMedia(BoxHttp.url(page), null)) {
            String mime = response.header("Content-Type", "");
            if (!response.isSuccessful() || !(mime.contains("text/html") || mime.contains("application/xhtml"))) return null;
            String html = new String(BoxHttp.read(response.body(), 1024 * 1024), "UTF-8");
            java.util.regex.Matcher tags = java.util.regex.Pattern.compile(
                    "(?is)<(?:video|audio|source)\\b[^>]*?\\s+src\\s*=\\s*([\"'])(.*?)\\1").matcher(html);
            while (tags.find()) {
                String value = android.text.Html.fromHtml(tags.group(2)).toString();
                okhttp3.HttpUrl media = response.request().url().resolve(value);
                if (media == null || !media.encodedPath().matches("(?i).*\\.(m3u8|mp4|flv|mpd|mkv|webm|mp3|m4a|aac|flac|wav)$")) continue;
                JSONObject headers = new JSONObject().put("Referer", response.request().url().toString());
                String agent = response.request().header("User-Agent");
                if (agent != null) headers.put("User-Agent", agent);
                StringBuilder cookies = new StringBuilder();
                for (okhttp3.Cookie cookie : okhttp3.Cookie.parseAll(response.request().url(), response.headers())) {
                    if (cookie.expiresAt() < System.currentTimeMillis() || !cookie.matches(media)) continue;
                    if (cookies.length() > 0) cookies.append("; ");
                    cookies.append(cookie.name()).append('=').append(cookie.value());
                }
                if (cookies.length() > 0) headers.put("Cookie", cookies.toString());
                return new JSONObject().put("url", media.toString()).put("parse", 0).put("header", headers);
            }
        } catch (Exception unavailable) { }
        return null;
    }

    private class LegacyClient extends WebViewClient {
        private final String page;
        private final CountDownLatch ready;
        private final AtomicReference<JSONObject> found;
        LegacyClient(String page, CountDownLatch ready, AtomicReference<JSONObject> found) {
            this.page = page; this.ready = ready; this.found = found;
        }
        void inspect(String url, Map<String, String> requestHeaders) {
            if (closed || ready.getCount() == 0 || url == null) return;
            try {
                okhttp3.HttpUrl address = BoxHttp.url(url);
                if (!address.encodedPath().matches("(?i).*\\.(m3u8|mp4|flv|mpd|mkv|webm|mp3|m4a|aac|flac|wav)$")) return;
                JSONObject headers = new JSONObject();
                for (Map.Entry<String, String> header : requestHeaders.entrySet())
                    if (header.getKey().matches("(?i)user-agent|referer|origin|cookie|authorization"))
                        headers.put(header.getKey(), header.getValue());
                if (!headers.has("Referer") && !headers.has("referer")) headers.put("Referer", page);
                if (!headers.has("User-Agent") && !headers.has("user-agent")) headers.put("User-Agent", userAgent);
                if (!headers.has("Cookie") && !headers.has("cookie")) {
                    try {
                        String cookie = android.webkit.CookieManager.getInstance().getCookie(url);
                        if (cookie != null && !cookie.isEmpty()) headers.put("Cookie", cookie);
                    } catch (RuntimeException unavailable) { }
                }
                if (found.compareAndSet(null, new JSONObject().put("url", BoxHttp.url(url).toString())
                        .put("parse", 0).put("header", headers))) ready.countDown();
            } catch (Exception ignored) { }
        }
        @Override public void onLoadResource(WebView view, String url) {
            if (android.os.Build.VERSION.SDK_INT < 21) inspect(url, Collections.emptyMap());
        }
        @Override public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
            inspect(url, Collections.emptyMap()); return null;
        }
        @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
            inspect(url, Collections.emptyMap());
            return url == null || !(url.startsWith("https://") || url.startsWith("http://"));
        }
        @Override public void onPageFinished(WebView view, String url) {
            if (android.os.Build.VERSION.SDK_INT >= 19) DomMedia.inspect(view, this);
        }
    }

    @android.annotation.TargetApi(19)
    private static final class DomMedia {
        static void inspect(WebView browser, LegacyClient client) {
            browser.evaluateJavascript("(function(){var out=[],nodes=document.querySelectorAll('video,audio,source');"
                    + "for(var i=0;i<nodes.length&&out.length<8;i++){var u=nodes[i].currentSrc||nodes[i].src;if(u)out.push(u);}return out;})()", value -> {
                try {
                    org.json.JSONArray urls = new org.json.JSONArray(value);
                    for (int i = 0; i < urls.length(); i++) client.inspect(urls.optString(i), Collections.emptyMap());
                } catch (Exception ignored) { }
            });
        }
    }

    @android.annotation.TargetApi(21)
    private final class ModernClient extends LegacyClient {
        ModernClient(String page, CountDownLatch ready, AtomicReference<JSONObject> found) { super(page, ready, found); }
        @Override public WebResourceResponse shouldInterceptRequest(WebView view, android.webkit.WebResourceRequest request) {
            inspect(request.getUrl().toString(), request.getRequestHeaders()); return null;
        }
    }

    private void clear() {
        if (browser == null) return;
        WebView previous = browser; browser = null;
        parent.removeView(previous);
        previous.stopLoading(); previous.destroy();
    }

    @Override public void close() {
        closed = true;
        CountDownLatch pending = waiting;
        if (pending != null) pending.countDown();
        main.post(this::clear);
    }
}
