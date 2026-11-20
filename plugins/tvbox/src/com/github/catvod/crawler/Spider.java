package com.github.catvod.crawler;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import com.github.catvod.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Baseline CatVod SPI for DEX spiders that keep the contract outside their JAR. */
public abstract class Spider {
    public String siteKey;
    private Context context;

    public final void attach(Context value) { context = value; }
    public Activity getActivity() { return context instanceof Activity ? (Activity)context : null; }
    public void init(Context context) throws Exception { }
    public void init(Context context, String extend) throws Exception { init(context); }
    public String homeContent(boolean filter) throws Exception { return "{\"class\":[]}"; }
    public String homeVideoContent() throws Exception { return "{\"list\":[]}"; }
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        return "{\"list\":[]}";
    }
    public String detailContent(List<String> ids) throws Exception { return "{\"list\":[]}"; }
    public String searchContent(String key, boolean quick) throws Exception { return "{\"list\":[]}"; }
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        return searchContent(key, quick);
    }
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception { return "{}"; }
    public String liveContent(String url) throws Exception { return ""; }
    public String action(String action) throws Exception { return "{}"; }
    public boolean manualVideoCheck() throws Exception { return false; }
    public boolean isVideoFormat(String url) throws Exception { return false; }
    public Object[] proxy(Map<String, String> params) throws Exception { return null; }
    public Object[] localProxy(Map<String, String> params) throws Exception { return proxy(params); }
    public void destroy() { }
    public void close() { }

    public String proxyUrl() { return proxyUrl(java.util.Collections.emptyMap()); }
    public String proxyUrl(Map<String, String> params) { return proxyUrl(params, true); }
    public String proxyUrl(Map<String, String> params, boolean local) {
        Uri.Builder result = Uri.parse(Proxy.getUrl(true)).buildUpon().appendQueryParameter("do", "jar")
                .appendQueryParameter("siteKey", siteKey);
        if (params != null) for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!"do".equals(entry.getKey()) && !"siteKey".equals(entry.getKey()))
                result.appendQueryParameter(entry.getKey(), entry.getValue());
        }
        return result.build().toString();
    }
}
