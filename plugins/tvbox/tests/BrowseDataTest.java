package xiao.bu.channel.tvbox;

import java.lang.reflect.*;
import java.util.*;
import org.json.*;

public final class BrowseDataTest {
    static void check(boolean value,String name){if(!value)throw new AssertionError(name);}
    public static void main(String[] args)throws Exception{
        BoxEpisodeLabel file=BoxEpisodeLabel.of("[2.48GB] (2026)海边来信.1080p.中文字幕.mkv");
        check(file.title.equals("(2026)海边来信.1080p.中文字幕")&&file.facts.equals("2.48GB · MKV"),"file metadata separated");
        check(BoxEpisodeLabel.file("演唱会.FLAC")&&!BoxEpisodeLabel.file("第 01 集"),"files and episodes distinguished");
        check(BoxEpisodeLabel.of("第 01 集").title.equals("第 01 集"),"ordinary labels retained");
        BoxDataCache cache=new BoxDataCache();JSONObject data=new JSONObject().put("list",new JSONArray().put(new JSONObject().put("vod_id","one")));
        cache.put("one",data);data.getJSONArray("list").getJSONObject(0).put("vod_id","changed");
        check(cache.get("one").getJSONArray("list").getJSONObject(0).getString("vod_id").equals("one"),"cache owns a copy");
        cache.put("empty",new JSONObject().put("list",new JSONArray()));check(cache.get("empty")==null,"empty and failed pages stay uncached");
        for(int i=0;i<30;i++)cache.put("page"+i,data);check(cache.get("page0")==null&&cache.get("page29")!=null,"bounded page count");
        Field storage=BoxDataCache.class.getDeclaredField("entries");storage.setAccessible(true);Map entries=(Map)storage.get(cache);Object entry=entries.get("page29");
        Constructor constructor=entry.getClass().getDeclaredConstructors()[0];constructor.setAccessible(true);entries.put("page29",constructor.newInstance(data.toString(),System.nanoTime()/1000000-120001));
        check(cache.get("page29")==null,"expired data is refreshed");cache.clear();check(cache.get("page28")==null,"authorization/config invalidation clears data");
        System.out.println("PASS file display metadata, independent bounded cache copies, expiry and invalidation");
    }
}
