package xiao.bu.channel.tvbox;

import android.util.LruCache;
import android.os.SystemClock;
import java.io.Closeable;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.HttpUrl;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONObject;

/** Keeps the provider's playback headers on manifests and their media requests. */
final class HuyaRelay implements Closeable, LocalSpiderProxy.Source {
    private static final Pattern URI = Pattern.compile("\\bURI=\"([^\"]+)\"");
    private static final String HLS = "application/vnd.apple.mpegurl";
    private final BoxHttp http;
    private final JSONObject headers;
    private final LocalSpiderProxy proxy;
    private final LruCache<String, HttpUrl> urls = new LruCache<>(512);
    private final Refresh refresh;
    private Playlist live;
    private long loadedAt;

    interface Refresh { Playlist playlist() throws Exception; }

    static final class Playlist {
        final HttpUrl address;
        final String content;
        Playlist(HttpUrl address, String content) {
            this.address = address;
            this.content = content;
        }
    }

    HuyaRelay(BoxHttp http, JSONObject headers, Playlist initial, Refresh refresh) throws IOException {
        this.http = http;
        this.headers = headers;
        this.live = initial;
        this.refresh = refresh;
        loadedAt = SystemClock.elapsedRealtime();
        proxy = new LocalSpiderProxy();
        proxy.set(this);
    }

    String liveUrl() {
        return proxy.url() + ".m3u8?id=live";
    }

    String url(HttpUrl address) throws IOException {
        if (!isMediaAddress(address)) throw new IOException("虎牙返回了无效的媒体地址");
        String id = UUID.nameUUIDFromBytes(address.toString().getBytes("UTF-8")).toString();
        urls.put(id, address);
        return proxy.url() + suffix(address) + "?id=" + id;
    }

    static boolean isMediaAddress(HttpUrl address) {
        return address != null && address.host().endsWith(".huya.com")
                && address.username().isEmpty() && address.password().isEmpty();
    }

    private static String suffix(HttpUrl address) {
        String path = address.encodedPath().toLowerCase(Locale.US);
        String suffix = path.substring(Math.max(path.lastIndexOf('.'), 0));
        return suffix.matches("\\.[a-z0-9]{1,6}") ? suffix : "";
    }

    @Override public Object[] proxy(Map<String, String> arguments) throws Exception {
        String id = arguments.get("id");
        if ("live".equals(id) && refresh != null) return livePlaylist();
        HttpUrl address = id == null ? null : urls.get(id);
        if (address == null) return null;
        boolean playlist = ".m3u8".equals(suffix(address));
        Response response = http.openMedia(address, headers, playlist ? java.util.Collections.emptyMap() : arguments);
        ResponseBody body = response.body();
        if (body == null) return null;
        if (!response.isSuccessful()) {
            int code = response.code();
            response.close();
            return new Object[]{code, "text/plain", null};
        }
        if (playlist) {
            try (Response manifest = response) {
                byte[] bytes = BoxHttp.read(body, 512 * 1024);
                String rewritten = playlist(new String(bytes, "UTF-8"), manifest.request().url());
                return new Object[]{200, HLS, rewritten};
            }
        }
        Map<String, String> range = new HashMap<>();
        for (String name : new String[]{"Accept-Ranges", "Content-Range"}) {
            String value = response.header(name);
            if (value != null) range.put(name, value);
        }
        if (body.contentLength() >= 0) range.put("Content-Length", String.valueOf(body.contentLength()));
        String mime = body.contentType() == null ? "application/octet-stream" : body.contentType().toString();
        return new Object[]{response.code(), mime, body.byteStream(), range};
    }

    private synchronized Object[] livePlaylist() throws Exception {
        long now = SystemClock.elapsedRealtime();
        if (now - loadedAt >= 1800L) {
            Playlist next = readPlaylist(http, live.address, headers);
            if (next == null) next = refresh.playlist();
            if (next == null) return new Object[]{502, "text/plain", null};
            live = next;
            loadedAt = SystemClock.elapsedRealtime();
        }
        return new Object[]{200, HLS, playlist(live.content, live.address)};
    }

    static Playlist readPlaylist(BoxHttp http, HttpUrl address, JSONObject headers) throws Exception {
        try (Response response = http.probeMedia(address, headers)) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null || !isMediaAddress(response.request().url())) return null;
            String content = new String(BoxHttp.read(body, 512 * 1024), "UTF-8");
            return content.startsWith("#EXTM3U") ? new Playlist(response.request().url(), content) : null;
        } catch (IOException unavailable) {
            return null;
        }
    }

    private String playlist(String text, HttpUrl base) throws IOException {
        StringBuilder playlist = new StringBuilder(text.length());
        for (String line : text.split("\\r?\\n", -1)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                line = url(base.resolve(trimmed));
            } else if (line.contains("URI=\"")) {
                Matcher match = URI.matcher(line);
                StringBuffer attributes = new StringBuffer();
                while (match.find()) {
                    String local = url(base.resolve(match.group(1)));
                    match.appendReplacement(attributes, Matcher.quoteReplacement("URI=\"" + local + "\""));
                }
                line = match.appendTail(attributes).toString();
            }
            playlist.append(line).append('\n');
        }
        return playlist.toString();
    }

    @Override public void close() {
        proxy.close();
        urls.evictAll();
    }
}
