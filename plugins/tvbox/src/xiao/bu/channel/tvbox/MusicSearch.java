package xiao.bu.channel.tvbox;

import java.io.IOException;
import java.lang.reflect.*;
import java.util.*;
import java.util.regex.*;
import okhttp3.HttpUrl;
import org.json.*;

/** Completes the Music spider's missing search using its own origin and existing session. */
final class MusicSearch {
    private static final String TRACK = "ntv-music-track:";
    static boolean supports(String api) { return "csp_MusicGuard".equals(api) || "csp_Music".equals(api); }
    private static Object fallbackMusic(Object wrapper) throws Exception {
        Object music = music(wrapper);
        if (music == null) return null;
        Method two = music.getClass().getMethod("searchContent", String.class, boolean.class);
        Method three = music.getClass().getMethod("searchContent", String.class, boolean.class, String.class);
        if (!two.getDeclaringClass().getName().equals("com.github.catvod.crawler.Spider")
                || !three.getDeclaringClass().getName().equals("com.github.catvod.crawler.Spider")) return null;
        return music;
    }
    static boolean prepare(Object wrapper) throws Exception {
        boolean initialized = false;
        // Guard can create the inner Music lazily on its first content call.
        if (music(wrapper) == null && wrapper.getClass().getName().equals("com.github.catvod.spider.MusicGuard")) {
            wrapper.getClass().getMethod("homeContent", boolean.class).invoke(wrapper, true);
            initialized = true;
        }
        if (fallbackMusic(wrapper) == null) return false;
        // Music initializes its web session in homeContent, rather than init.
        if (!initialized) wrapper.getClass().getMethod("homeContent", boolean.class).invoke(wrapper, true);
        return true;
    }
    static JSONObject search(Object wrapper, BoxHttp http, String query, int page) throws Exception {
        Object music = fallbackMusic(wrapper);
        if (music == null) return null;
        if (page > 1) return new JSONObject().put("list", new JSONArray()).put("pagecount", 1);
        Session session = session(music);
        HttpUrl url = BoxHttp.url(session.origin).resolve("/so.php").newBuilder().addQueryParameter("wd", query).build();
        return parse(content(session.request(http, url.toString(), new JSONObject())), session.origin);
    }
    private static final class Session {
        String origin, cookie;
        JSONObject request(BoxHttp http, String url, JSONObject options) throws Exception {
            JSONObject headers = new JSONObject().put("Referer", origin).put("User-Agent", "Mozilla/5.0");
            if (!cookie.isEmpty()) headers.put("Cookie", cookie);
            return http.request(url, options.put("headers", headers));
        }
    }
    private static Session session(Object music) throws Exception {
        String origin = "", cookie = "";
        for (Field field : music.getClass().getDeclaredFields()) {
            if (field.getType() != String.class) continue;
            field.setAccessible(true); Object value = field.get(music);
            if (!(value instanceof String)) continue;
            String text = (String) value;
            HttpUrl url = HttpUrl.parse(text);
            if (url != null && "/".equals(url.encodedPath()) && url.query() == null) origin = url.toString();
            else if (text.startsWith("PHPSESSID=") && text.indexOf('\n') < 0 && text.indexOf('\r') < 0) cookie = text;
        }
        if (origin.isEmpty()) throw new IOException("音乐爬虫没有提供搜索站点地址");
        Session session = new Session();session.origin = origin;session.cookie = cookie;return session;
    }
    private static String content(JSONObject reply) throws IOException {
        if (reply.optInt("code", 200) >= 400) throw new IOException("音乐搜索 HTTP " + reply.optInt("code"));
        String html = reply.optString("content");
        if (html.contains("verifyForm") || html.contains("human_check"))
            throw new IOException("音乐站点需要完成验证，请先在原站验证后重试");
        return html;
    }
    /** Use the site's public web player only when the original Music player returned no data. */
    static JSONObject play(Object wrapper, BoxHttp http, String id) throws Exception {
        Object music = music(wrapper);
        if (music == null || !id.matches("/mp3/[A-Za-z0-9_-]+\\.html")) return null;
        Session session = session(music);
        JSONObject page = session.request(http, BoxHttp.resolve(session.origin, id), new JSONObject());
        Matcher call = Pattern.compile("\\bplayer\\(\\s*['\"]music['\"]\\s*,\\s*['\"]([A-Za-z0-9_-]+)['\"]\\s*\\)").matcher(content(page));
        if (!call.find()) throw new IOException("音乐站点没有提供在线播放信息");
        long now = System.currentTimeMillis();
        JSONObject responseHeaders = page.optJSONObject("headers");
        for (Iterator<String> names = responseHeaders == null ? Collections.<String>emptyList().iterator() : responseHeaders.keys(); names.hasNext();) {
            String name = names.next();if (!name.equalsIgnoreCase("Date")) continue;
            try { now = new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(responseHeaders.getString(name)).getTime(); }
            catch (java.text.ParseException ignored) { }
        }
        java.text.SimpleDateFormat clock = new java.text.SimpleDateFormat("yyyyMMddHHmm", Locale.US);
        clock.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        String body = "id=" + call.group(1) + "&type=music&key=" + clock.format(new Date(now)) + "4c44";
        JSONObject reply = session.request(http, BoxHttp.resolve(session.origin, "/js/play.php"),
                new JSONObject().put("method", "POST").put("body", body));
        JSONObject data = new JSONObject(content(reply));
        if (data.optInt("msg") != 1 || HttpUrl.parse(data.optString("url")) == null)
            throw new IOException("音乐站点暂未提供可播放地址");
        return new JSONObject().put("url", data.getString("url")).put("parse", 0)
                .put("cover", data.optString("pic"));
    }
    private static Object music(Object object) throws IllegalAccessException {
        if (object.getClass().getName().equals("com.github.catvod.spider.Music")) return object;
        for (Class<?> type = object.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!field.getType().getName().contains("Spider")) continue;
                field.setAccessible(true); Object child = field.get(object);
                if (child != null && child.getClass().getName().equals("com.github.catvod.spider.Music")) return child;
            }
        }
        return null;
    }
    static JSONObject parse(String html, String origin) throws Exception {
        JSONArray list = new JSONArray();
        Matcher section = Pattern.compile("<div\\b[^>]*\\bclass\\s*=\\s*['\"][^'\"]*\\bplay_list\\b[^'\"]*['\"][^>]*>", Pattern.CASE_INSENSITIVE).matcher(html);
        if (!section.find()) {
            if (html.contains("搜索结果") || html.contains("暂无") || html.contains("没有找到"))
                return new JSONObject().put("list", list).put("pagecount", 1);
            throw new IOException("音乐搜索页面格式已变化");
        }
        Matcher closing = Pattern.compile("</ul\\s*>", Pattern.CASE_INSENSITIVE).matcher(html);
        int end = closing.find(section.end()) ? closing.start() : -1;
        String body = html.substring(section.end(), end < 0 ? html.length() : end);
        Matcher links = Pattern.compile("<a\\b([^>]*)>([\\s\\S]*?)</a>", Pattern.CASE_INSENSITIVE).matcher(body);
        Set<String> ids = new HashSet<>(); HttpUrl base = BoxHttp.url(origin);
        while (links.find() && list.length() < 120) {
            String href = attribute(links.group(1), "href");
            HttpUrl url = base.resolve(href);
            if (url == null || !url.host().equals(base.host()) || !url.encodedPath().matches("/(?:mp3|mp4|singer|album)/[A-Za-z0-9_-]+\\.html")) continue;
            String name = plain(links.group(2));
            if (name.isEmpty()) name = plain(attribute(links.group(1), "title"));
            if (name.isEmpty() || !ids.add(url.encodedPath())) continue;
            String id = url.encodedPath();
            // Native Music.detailContent only accepts playlists, while playerContent accepts tracks.
            // Keep the song's name and original player ID together across search sessions.
            if (id.startsWith("/mp3/") || id.startsWith("/mp4/"))
                id = TRACK + java.net.URLEncoder.encode(new JSONObject().put("path", id).put("name", name).toString(), "UTF-8");
            list.put(new JSONObject().put("vod_id", id).put("vod_name", name)
                    .put("vod_remarks", "音乐").put("vod_type", "音乐").put("vod_pic", ""));
        }
        return new JSONObject().put("list", list).put("page", 1).put("pagecount", 1);
    }
    static JSONObject detail(String id) throws Exception {
        if (!id.startsWith(TRACK)) return null;
        JSONObject track = new JSONObject(java.net.URLDecoder.decode(id.substring(TRACK.length()), "UTF-8"));
        String path = track.getString("path"), name = track.getString("name");
        if (!path.matches("/(?:mp3|mp4)/[A-Za-z0-9_-]+\\.html") || name.isEmpty())
            throw new IOException("音乐搜索结果无效，请重新搜索");
        String label = name.replace('$', '＄').replace('#', '＃');
        JSONObject song = new JSONObject().put("vod_id", id).put("vod_name", name).put("vod_type", "音乐")
                .put("vod_play_from", "易听").put("vod_play_url", label + "$" + path);
        return new JSONObject().put("list", new JSONArray().put(song));
    }
    private static String attribute(String attributes, String name) {
        Matcher match = Pattern.compile("\\b" + name + "\\s*=\\s*(['\"])(.*?)\\1", Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(attributes);
        return match.find() ? match.group(2).replace("&amp;", "&") : "";
    }
    private static String plain(String html) {
        String text = html.replaceAll("<[^>]*>", "").replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">");
        Matcher entities = Pattern.compile("&#(x[0-9a-fA-F]+|[0-9]+);").matcher(text);
        StringBuffer result = new StringBuffer();
        while (entities.find()) {
            try {
                String number = entities.group(1); int code = Integer.parseInt(number.startsWith("x") ? number.substring(1) : number, number.startsWith("x") ? 16 : 10);
                entities.appendReplacement(result, Matcher.quoteReplacement(new String(Character.toChars(code))));
            } catch (IllegalArgumentException invalid) { entities.appendReplacement(result, Matcher.quoteReplacement(entities.group())); }
        }
        entities.appendTail(result);return result.toString().replaceAll("\\s+", " ").trim();
    }
}
