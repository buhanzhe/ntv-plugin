package com.github.catvod;

/** CatVod's local playback proxy address for the current nTv TVBox channel. */
public final class Proxy {
    private static volatile String address;
    public static void configure(String url) { address = url; }
    public static String getUrl() { return getUrl(true); }
    public static String getUrl(boolean local) {
        if (address == null) throw new IllegalStateException("Playback proxy is closed");
        return address;
    }
    public static int getPort() { return android.net.Uri.parse(getUrl()).getPort(); }
}
