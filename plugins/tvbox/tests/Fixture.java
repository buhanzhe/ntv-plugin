package com.github.catvod.spider;

import android.content.Context;
import com.github.catvod.crawler.Spider;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

public final class Fixture extends Spider {
    private String media;
    @Override public void init(Context context, String extend) { media = extend; }

    @Override public String homeContent(boolean filter) throws Exception {
        return detailContent(java.util.Collections.singletonList("1"));
    }

    @Override public String detailContent(List<String> ids) throws Exception {
        return new JSONObject().put("list", new JSONArray().put(new JSONObject()
                .put("vod_id", "1").put("vod_name", "代理样本")
                .put("vod_play_from", "网盘代理").put("vod_play_url", "代理播放$1"))).toString();
    }

    @Override public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        Map<String, String> args = new HashMap<>();
        args.put("id", id);
        return new JSONObject().put("url", proxyUrl(args)).put("parse", 0).toString();
    }

    @Override public Object[] proxy(Map<String, String> params) throws Exception {
        if (!"1".equals(params.get("id"))) return null;
        HttpURLConnection connection = (HttpURLConnection) new URL(media).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestProperty("X-Fixture", "verified");
        if (params.containsKey("range")) connection.setRequestProperty("Range", params.get("range"));
        Map<String, String> headers = new HashMap<>();
        for (String name : new String[]{"Accept-Ranges", "Content-Length", "Content-Range"}) {
            String value = connection.getHeaderField(name);
            if (value != null) headers.put(name, value);
        }
        return new Object[]{connection.getResponseCode(), "video/mp4", connection.getInputStream(), headers};
    }
}
