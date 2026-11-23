package xiao.bu.channel.tvbox;

import java.io.Closeable;
import java.io.IOException;
import okhttp3.HttpUrl;
import org.json.JSONArray;
import org.json.JSONObject;

/** Resolve official public room pages when a spider returns a page for parsing. */
final class HuyaLive implements Closeable {
    private static final String PROFILE = "https://mp.huya.com/cache.php";
    private static final String AGENT = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36";
    private final BoxHttp http;
    private HuyaRelay relay;

    HuyaLive(BoxHttp http) {
        this.http = http;
    }

    JSONObject resolve(String url) throws Exception {
        HttpUrl page = HttpUrl.parse(url);
        if (page == null || !page.username().isEmpty() || !page.password().isEmpty()
                || !("m.huya.com".equals(page.host()) || "www.huya.com".equals(page.host())
                || "huya.com".equals(page.host())) || !page.encodedPath().matches("/[0-9]{1,16}/?")) return null;

        HttpUrl api = BoxHttp.url(PROFILE).newBuilder()
                .addQueryParameter("m", "Live")
                .addQueryParameter("do", "profileRoom")
                .addQueryParameter("roomid", page.pathSegments().get(0))
                .build();
        JSONObject headers = new JSONObject().put("User-Agent", AGENT).put("Referer", "https://www.huya.com/");
        JSONObject stream = streams(api, headers);
        HuyaRelay.Playlist playlist = playlist(stream.optJSONObject("hls"), headers);
        HttpUrl media = playlist == null ? flv(stream.optJSONObject("flv"), headers) : playlist.address;
        if (media == null) throw new IOException("虎牙返回的公开线路暂不可用");
        close();
        relay = new HuyaRelay(http, headers, playlist,
                playlist == null ? null : () -> playlist(streams(api, headers).optJSONObject("hls"), headers));
        return new JSONObject().put("url", playlist == null ? relay.url(media) : relay.liveUrl());
    }

    private JSONObject streams(HttpUrl api, JSONObject headers) throws Exception {
        JSONObject response = http.request(api.toString(), new JSONObject().put("headers", headers));
        if (response.optInt("code") != 200) throw new IOException("虎牙直播接口 HTTP " + response.optInt("code"));
        JSONObject profile = new JSONObject(response.getString("content"));
        JSONObject data = profile.optJSONObject("data");
        JSONObject stream = data == null ? null : data.optJSONObject("stream");
        if (profile.optInt("status") != 200 || stream == null || data.optBoolean("isRoomPay")
                || data.optInt("isRoomPay") != 0) throw new IOException("虎牙直播间当前没有公开线路");
        return stream;
    }

    private HuyaRelay.Playlist playlist(JSONObject group, JSONObject headers) throws Exception {
        JSONArray lines = group == null ? null : group.optJSONArray("multiLine");
        for (int i = 0; lines != null && i < Math.min(lines.length(), 3); i++) {
            HttpUrl url = mediaUrl(lines.optJSONObject(i), ".m3u8");
            if (url == null) continue;
            HuyaRelay.Playlist playlist = HuyaRelay.readPlaylist(http, url, headers);
            if (playlist != null) return playlist;
        }
        return null;
    }

    private HttpUrl flv(JSONObject group, JSONObject headers) throws Exception {
        JSONArray lines = group == null ? null : group.optJSONArray("multiLine");
        for (int i = 0; lines != null && i < Math.min(lines.length(), 3); i++) {
            HttpUrl url = mediaUrl(lines.optJSONObject(i), ".flv");
            if (url != null && http.hasMediaPrefix(url, headers, "FLV")) return url;
        }
        return null;
    }

    private static HttpUrl mediaUrl(JSONObject row, String extension) {
        HttpUrl url = row == null ? null : HttpUrl.parse(row.optString("url"));
        return HuyaRelay.isMediaAddress(url) && url.encodedPath().endsWith(extension) ? url : null;
    }

    @Override public void close() {
        if (relay != null) {
            relay.close();
            relay = null;
        }
    }
}
