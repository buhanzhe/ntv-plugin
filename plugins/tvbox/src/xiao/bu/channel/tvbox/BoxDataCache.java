package xiao.bu.channel.tvbox;

import java.util.*;
import org.json.*;

/** Bounded copies of successful browsing data; playback responses are never cached. */
final class BoxDataCache {
    private static final class Entry {final String json;final long at;Entry(String json,long at){this.json=json;this.at=at;}}
    private final LinkedHashMap<String,Entry> entries=new LinkedHashMap<>(24,.75f,true);
    private int characters;
    JSONObject get(String key){
        Entry entry;
        synchronized(this){entry=fresh(key);}
        if(entry==null)return null;
        try{return new JSONObject(entry.json);}catch(JSONException invalid){return null;}
    }
    synchronized boolean contains(String key){return fresh(key)!=null;}
    private Entry fresh(String key){
        Entry entry=entries.get(key);
        if(entry!=null&&System.nanoTime()/1000000-entry.at>120000){
            entries.remove(key);characters-=entry.json.length();return null;
        }
        return entry;
    }
    static String encode(JSONObject data){
        JSONArray list=data.optJSONArray("list");if(list==null||list.length()==0)return null;
        String json=data.toString();
        return json.length() <= 262144 ? json : null;
    }
    void put(String key,JSONObject data){putEncoded(key,encode(data));}
    synchronized void putEncoded(String key,String json){
        if(json==null)return;
        Entry previous=entries.put(key,new Entry(json,System.nanoTime()/1000000));if(previous!=null)characters-=previous.json.length();characters+=json.length();
        while(entries.size()>24||characters>1048576){Iterator<Entry> iterator=entries.values().iterator();Entry old=iterator.next();characters-=old.json.length();iterator.remove();}
    }
    synchronized void clear(){entries.clear();characters=0;}
}
