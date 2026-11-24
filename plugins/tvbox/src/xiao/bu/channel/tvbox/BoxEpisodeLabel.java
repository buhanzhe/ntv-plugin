package xiao.bu.channel.tvbox;

import java.util.Locale;
import java.util.regex.*;

/** Display-only file metadata. The original episode name and playback identifier remain untouched. */
final class BoxEpisodeLabel {
    private static final Pattern EXT=Pattern.compile("(?i)\\.(mp4|mkv|m4v|ts|mov|flv|avi|m2ts|mp3|flac|m4a|aac|wav)$");
    private static final Pattern SIZE=Pattern.compile("(?i)^\\s*[\\[【]\\s*(\\d+(?:\\.\\d+)?\\s*(?:[KMGT]i?B))\\s*[\\]】]\\s*");
    final String title,facts;
    private BoxEpisodeLabel(String title,String facts){this.title=title;this.facts=facts;}
    static boolean file(String name){return EXT.matcher(name.trim()).find()||name.codePointCount(0,name.length())>28;}
    static BoxEpisodeLabel of(String raw){
        String name=raw.trim(),facts="";Matcher size=SIZE.matcher(name);
        if(size.find()){facts=size.group(1);name=name.substring(size.end());}
        Matcher extension=EXT.matcher(name);
        if(extension.find()){facts+=(facts.isEmpty()?"":" · ")+extension.group(1).toUpperCase(Locale.US);name=name.substring(0,extension.start());}
        return new BoxEpisodeLabel(name.isEmpty()?raw:name,facts);
    }
}
