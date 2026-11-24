package com.github.catvod.crawler;
/** Minimum upstream contract used by the lazy MusicGuard regression fixture. */
public class Spider {
    public String searchContent(String query, boolean quick) { return "{\"list\":[]}"; }
    public String searchContent(String query, boolean quick, String page) { return searchContent(query, quick); }
    public String homeContent(boolean filter) { return "{}"; }
}
