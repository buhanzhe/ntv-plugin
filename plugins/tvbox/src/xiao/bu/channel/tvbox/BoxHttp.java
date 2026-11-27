package xiao.bu.channel.tvbox;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.ByteString;
import org.json.JSONObject;

final class BoxHttp {
    static final int CONTENT_LIMIT = 8 * 1024 * 1024;
    private final OkHttpClient client;

    BoxHttp(OkHttpClient client) { this.client = client; }
    BoxHttp isolated() {
        return new BoxHttp(client.newBuilder().dispatcher(new okhttp3.Dispatcher()).build());
    }
    BoxHttp forArtwork(){return new BoxHttp(client.newBuilder().dispatcher(new okhttp3.Dispatcher())
            .connectTimeout(4,TimeUnit.SECONDS).readTimeout(7,TimeUnit.SECONDS).callTimeout(10,TimeUnit.SECONDS).build());}
    BoxHttp forSearch() {
        return new BoxHttp(client.newBuilder().dispatcher(new okhttp3.Dispatcher())
                .connectTimeout(3, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS)
                .writeTimeout(6, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS).build());
    }
    void cancelRequests() { client.dispatcher().cancelAll(); }

    static HttpUrl url(String value) throws IOException {
        HttpUrl result = HttpUrl.parse(value);
        if (result == null || !result.username().isEmpty() || !result.password().isEmpty())
            throw new IOException("需要有效的 HTTP 或 HTTPS 地址");
        return result;
    }

    static String resolve(String base, String value) throws IOException {
        HttpUrl result = url(base).resolve(value);
        if (result == null) throw new IOException("配置中的资源地址无效");
        return url(result.toString()).toString();
    }

    Response get(String url) throws IOException {
        return client.newCall(new Request.Builder().url(url(url)).build()).execute();
    }

    String text(String url, int limit) throws IOException {
        try (Response response = get(url)) {
            if (!response.isSuccessful()) throw new IOException("HTTP " + response.code());
            return new String(read(response.body(), limit), "UTF-8");
        }
    }

    JSONObject request(String target, JSONObject options) throws Exception {
        Request.Builder request = new Request.Builder().url(url(target));
        headers(request, options.optJSONObject("headers"));
        String method = options.optString("method", "GET").toUpperCase(java.util.Locale.US);
        if (!method.matches("GET|HEAD|POST|PUT|PATCH|DELETE|OPTIONS")) throw new IOException("无效 HTTP 方法");
        if ("GET".equals(method) || "HEAD".equals(method)) request.method(method, null);
        else request.method(method, RequestBody.create(MediaType.parse(options.optString("contentType",
                "application/x-www-form-urlencoded")), options.optString("body", options.optString("data", ""))));
        OkHttpClient transport = options.optInt("redirect", 1) == 0
                ? client.newBuilder().followRedirects(false).followSslRedirects(false).build() : client;
        try (Response response = transport.newCall(request.build()).execute()) {
            JSONObject resultHeaders = new JSONObject();
            for (String name : response.headers().names()) resultHeaders.put(name, response.header(name));
            return new JSONObject().put("code", response.code()).put("headers", resultHeaders)
                    .put("url", response.request().url().toString())
                    .put("content", new String(read(response.body(), CONTENT_LIMIT), "UTF-8"));
        }
    }

    boolean hasMediaPrefix(HttpUrl address, JSONObject headers, String prefix) throws Exception {
        try (Response response = probeMedia(address, headers)) {
            ResponseBody body = response.body();
            return response.isSuccessful() && body != null
                    && body.source().rangeEquals(0, ByteString.encodeUtf8(prefix));
        } catch (IOException unavailable) {
            return false;
        }
    }

    Response probeMedia(HttpUrl address, JSONObject headers) throws Exception {
        Request.Builder request = new Request.Builder().url(address);
        headers(request, headers);
        return client.newBuilder().callTimeout(4, TimeUnit.SECONDS).build().newCall(request.build()).execute();
    }

    void checkPlayback(HttpUrl address, JSONObject headers) throws Exception {
        try (Response response = probeMedia(address, headers)) {
            if (!response.isSuccessful()) throw new IOException("线路暂不可用（HTTP " + response.code() + "）");
            ResponseBody body = response.body();
            if (body == null || body.contentLength() == 0) throw new IOException("线路没有返回媒体内容");
            MediaType type = body.contentType();
            if (type != null && ("html".equals(type.subtype()) || "json".equals(type.subtype()))) {
                throw new IOException("线路返回了网页或接口提示");
            }
        }
    }

    Response openMedia(HttpUrl address, JSONObject headers, Map<String, String> options) throws Exception {
        return mediaCall(address, headers, options).execute();
    }

    okhttp3.Call mediaCall(HttpUrl address, JSONObject headers, Map<String, String> options) throws Exception {
        Request.Builder request = new Request.Builder().url(address);
        headers(request, headers);
        String range = options.get("range"), ifRange = options.get("if-range");
        if (range != null) request.header("Range", range);
        if (ifRange != null) request.header("If-Range", ifRange);
        return client.newCall(request.build());
    }

    private static void headers(Request.Builder request, JSONObject headers) throws Exception {
        if (headers != null) for (Iterator<String> keys = headers.keys(); keys.hasNext();) {
            String name = keys.next();
            request.header(name, headers.getString(name));
        }
    }

    static byte[] read(ResponseBody body, int limit) throws IOException {
        if (body == null) return new byte[0];
        try (InputStream stream = body.byteStream()) { return read(stream, limit); }
    }

    static byte[] read(InputStream stream, int limit) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int count; (count = stream.read(buffer)) != -1;) {
            if (result.size() + count > limit) throw new IOException("站源响应过大");
            result.write(buffer, 0, count);
        }
        return result.toByteArray();
    }
}
