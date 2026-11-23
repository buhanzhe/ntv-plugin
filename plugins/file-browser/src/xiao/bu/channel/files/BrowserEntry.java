package xiao.bu.channel.files;

import java.util.Locale;

final class BrowserEntry implements Comparable<BrowserEntry> {
    final String name, location;
    final boolean directory;
    final int kind; // 0 filesystem, 1 SAF document, 2 SMB
    BrowserEntry(String name, String location, boolean directory, int kind) {
        this.name = name; this.location = location; this.directory = directory; this.kind = kind;
    }
    static boolean video(String name) {
        String lower = name.toLowerCase(Locale.US);
        return lower.matches(".*\\.(mp4|mkv|avi|mov|wmv|flv|webm|m4v|ts|m2ts|mts|mpg|mpeg|vob|3gp|rm|rmvb|asf|divx|ogv|f4v)$");
    }
    public int compareTo(BrowserEntry other) {
        if (directory != other.directory) return directory ? -1 : 1;
        return name.compareToIgnoreCase(other.name);
    }
    public String toString() { return (directory ? "[文件夹]  " : "[视频]  ") + name; }
}
