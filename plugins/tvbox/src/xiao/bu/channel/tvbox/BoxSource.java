package xiao.bu.channel.tvbox;

import android.content.Context;
import java.io.Closeable;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import okhttp3.HttpUrl;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import xiao.bu.tv.jar.ChannelPlugin;

final class BoxSource implements Closeable {
    interface Spider {
        JSONObject invoke(String method, Object... args) throws Exception;
        void close();
    }

    private final BoxHttp http;
    private final Context context;
    private final ChannelPlugin.VodHost host;
    private final ClassLoader assets;
    private String origin;
    private JSONObject config;
    private Site current;
    private Spider spider;
    private final JavaSpider.Jars jars;
    private final HuyaLive huya;

    BoxSource(Context context, ChannelPlugin.VodHost host, ClassLoader assets, BoxHttp http) {
        this.context = context;
        this.host = host;
        this.assets = assets;
        this.http = http;
        jars = new JavaSpider.Jars(context, http);
        huya = new HuyaLive(http);
    }

    static final class Site {
        final JSONObject config;
        final String origin;
        final int type;
        final String key, name, api, ext, jar;
        Site(JSONObject data, JSONObject config, String origin) {
            this.config = config;
            this.origin = origin;
            type = data.optInt("type", -1);
            key = data.optString("key");
            name = data.optString("name", key);
            api = data.optString("api");
            ext = data.isNull("ext") ? "" : data.optString("ext");
            jar = data.optString("jar");
        }
        boolean java() { return type == 3 && api.startsWith("csp_"); }
        boolean python() { return type == 3 && api.split("[?#]", 2)[0].endsWith(".py"); }
        boolean javascript() { return type == 3 && api.split("[?#]", 2)[0].endsWith(".js"); }
        boolean executable() { return java() || python() || javascript(); }
        boolean supported() { return !api.isEmpty() && (type == 0 || type == 1 || type == 4 || executable()); }
    }

    static final class Repository {
        final String name, url;
        Repository(String name, String url) { this.name = name; this.url = url; }
    }

    void load(String url) throws Exception {
        try (Response response = http.get(url)) {
            if (!response.isSuccessful()) throw new IOException("配置请求失败 HTTP " + response.code());
            JSONObject loaded = BoxConfig.decode(BoxHttp.read(response.body(), BoxHttp.CONTENT_LIMIT));
            if (loaded.optJSONArray("sites") == null && loaded.optJSONArray("urls") == null)
                throw new IOException("配置里没有 sites 或 urls");
            closeSpider();
            config = loaded;
            origin = response.request().url().toString();
        }
    }

    List<Repository> repositories() throws IOException {
        List<Repository> result = new ArrayList<>();
        JSONArray urls = config.optJSONArray("urls");
        if (urls != null) for (int i = 0; i < Math.min(300, urls.length()); i++) {
            JSONObject row = urls.optJSONObject(i);
            if (row == null || row.optString("url").isEmpty()) continue;
            try { result.add(new Repository(row.optString("name", "配置 " + (i + 1)), resource(row.getString("url")))); }
            catch (Exception ignored) { }
        }
        return result;
    }

    List<Site> sites() {
        List<Site> result = new ArrayList<>();
        JSONArray sites = config.optJSONArray("sites");
        if (sites != null) for (int i = 0; i < Math.min(600, sites.length()); i++) {
            JSONObject row = sites.optJSONObject(i);
            if (row == null) continue;
            Site site = new Site(row, config, origin);
            if (site.supported()) result.add(site);
        }
        return result;
    }

    String scriptLocation(Site site) throws IOException {
        if (!site.java()) return resource(site.api);
        String jar = site.jar.isEmpty() ? config.optString("spider") : site.jar;
        if (jar.isEmpty()) throw new IOException("配置未提供 CatVod JAR");
        String[] parts = jar.split(";md5;", 2);
        return resource(parts[0].trim()) + (parts.length == 2 ? ";md5;" + parts[1].trim() : "");
    }

    void select(Site site) throws Exception {
        if (current == site) return;
        closeSpider();
        origin = site.origin;
        config = site.config;
        String extend = site.ext;
        if (extend.startsWith("./") || extend.startsWith("../")) extend = resource(extend);
        if (site.java()) spider = jars.open(site, scriptLocation(site), extend);
        else if (site.python()) spider = ScriptSpider.python(host, http, scriptLocation(site), extend, site.key);
        else if (site.javascript()) spider = new ScriptSpider.Js(host, http, assets, scriptLocation(site), extend, site.key);
        current = site;
    }

    JSONObject home() throws Exception {
        return spider == null ? cms(null, null, null, 1)
                : spider.invoke("homeContent", true);
    }

    JSONObject recommendations() throws Exception {
        return spider == null ? cms(null, null, null, 1) : spider.invoke("homeVideoContent");
    }

    JSONObject category(String category, int page, JSONObject filters) throws Exception {
        return spider == null ? cms(category, null, null, page)
                : spider.invoke("categoryContent", category, String.valueOf(page), true, filters);
    }

    JSONObject search(String query, int page) throws Exception {
        return spider == null ? cms(null, null, query, page)
                : spider.invoke("searchContent", query, false, String.valueOf(page));
    }

    JSONObject detail(String id) throws Exception {
        return spider == null ? cms(null, id, null, 1) : spider.invoke("detailContent", new JSONArray().put(id));
    }

    JSONObject action(String action) throws Exception {
        if (current == null || !current.java()) throw new IOException("站点未提供此操作");
        return spider.invoke("action", action);
    }

    JSONObject play(String flag, String id) throws Exception {
        JSONArray flags = config.optJSONArray("flags");
        JSONObject result = spider == null
                ? current.type == 4 ? httpPlay(flag, id) : new JSONObject().put("url", id)
                : spider.invoke("playerContent", flag, id, flags == null ? new JSONArray() : flags);
        String url = result.optString("url");
        if (url.isEmpty()) throw new IOException("站点没有返回可播放地址");
        if (result.optInt("parse") != 0 || result.optInt("jx") != 0) {
            JSONObject parsed = parse(flag, result.optString("playUrl"), url);
            if (parsed.opt("header") == null && result.opt("header") != null) parsed.put("header", result.opt("header"));
            result = parsed;
        }
        result.put("url", BoxHttp.resolve(resource(current.type == 3 ? origin : current.api), result.getString("url")));
        return result;
    }

    private JSONObject parse(String flag, String prefix, String url) throws Exception {
        if (!prefix.isEmpty()) return new JSONObject(http.text(resource(prefix + url), BoxHttp.CONTENT_LIMIT));
        JSONArray parsers = config.optJSONArray("parses");
        if (parsers != null) for (int i = 0; i < parsers.length(); i++) {
            JSONObject parser = parsers.optJSONObject(i);
            if (parser == null || parser.optInt("type") != 1) continue;
            JSONObject ext = parser.optJSONObject("ext");
            JSONArray flags = ext == null ? null : ext.optJSONArray("flag");
            if (flags != null && flags.length() > 0 && !flags.toString().contains(JSONObject.quote(flag))) continue;
            try {
                JSONObject result = new JSONObject(http.text(resource(parser.getString("url") + url), BoxHttp.CONTENT_LIMIT));
                if (!result.optString("url").isEmpty()) return result;
            } catch (Exception ignored) { }
        }
        JSONObject live = huya.resolve(url);
        if (live != null) return live;
        throw new IOException("该线路需要站源提供可用的 JSON 解析接口");
    }

    private JSONObject httpPlay(String flag, String id) throws Exception {
        HttpUrl.Builder url = api().addQueryParameter("play", id).addQueryParameter("flag", flag);
        return new JSONObject(http.text(url.build().toString(), BoxHttp.CONTENT_LIMIT));
    }

    private JSONObject cms(String category, String id, String query, int page) throws Exception {
        HttpUrl.Builder url = api();
        if (current.type == 4) {
            url.addQueryParameter("filter", "true");
        } else if (id != null || category != null || query != null) {
            url.addQueryParameter("ac", current.type == 0 ? "videolist" : "detail");
        }
        if (category != null) url.addQueryParameter("t", category);
        if (id != null) url.addQueryParameter("ids", id);
        if (query != null) url.addQueryParameter("wd", query);
        if (category != null || query != null) url.addQueryParameter("pg", String.valueOf(page));
        String body = http.text(url.build().toString(), BoxHttp.CONTENT_LIMIT).trim();
        return body.startsWith("<") ? xml(body) : new JSONObject(body);
    }

    private HttpUrl.Builder api() throws IOException {
        HttpUrl.Builder url = BoxHttp.url(resource(current.api)).newBuilder();
        if (current.type == 4 && !current.ext.isEmpty()) url.addQueryParameter("extend", current.ext);
        return url;
    }

    private static JSONObject xml(String body) throws Exception {
        XmlPullParser parser = android.util.Xml.newPullParser();
        parser.setInput(new StringReader(body));
        JSONArray classes = new JSONArray(), items = new JSONArray();
        JSONObject result = new JSONObject().put("class", classes).put("list", items);
        JSONObject video = null;
        List<String> flags = new ArrayList<>(), episodes = new ArrayList<>();
        for (int event = parser.next(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
            String name = parser.getName();
            if (event == XmlPullParser.START_TAG) {
                if ("ty".equals(name)) {
                    String key = parser.getAttributeValue(null, "id");
                    classes.put(new JSONObject().put("type_id", key).put("type_name", parser.nextText()));
                } else if ("list".equals(name)) {
                    result.put("pagecount", parser.getAttributeValue(null, "pagecount"));
                } else if ("video".equals(name)) {
                    video = new JSONObject();
                    flags.clear(); episodes.clear();
                } else if (video != null && "dd".equals(name)) {
                    flags.add(parser.getAttributeValue(null, "flag"));
                    episodes.add(parser.nextText());
                } else if (video != null) {
                    String field = "id".equals(name) ? "vod_id" : "name".equals(name) ? "vod_name"
                            : "pic".equals(name) ? "vod_pic" : "note".equals(name) ? "vod_remarks"
                            : "des".equals(name) ? "vod_content" : null;
                    if (field != null) video.put(field, parser.nextText());
                }
            } else if (event == XmlPullParser.END_TAG && "video".equals(name) && video != null) {
                video.put("vod_play_from", android.text.TextUtils.join("$$$", flags));
                video.put("vod_play_url", android.text.TextUtils.join("$$$", episodes));
                items.put(video);
                video = null;
            }
        }
        return result;
    }

    private String resource(String value) throws IOException { return BoxHttp.resolve(origin, value); }

    private void closeSpider() {
        huya.close();
        if (spider != null) spider.close();
        spider = null;
        current = null;
    }

    public void close() {
        try { closeSpider(); } finally { jars.close(); }
    }
}
