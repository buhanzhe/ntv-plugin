package com.github.catvod.spider;

import android.content.Context;
import com.github.catvod.crawler.Spider;
import java.util.*;
import org.json.*;

/** Owned artwork/media and deterministic slow/fast routes for native browsing checks. */
public final class BrowseFixture extends Spider {
    public static int categories,details,homes,inits;
    private String base,mode;
    private boolean initialized;
    public void init(Context context,String extend){inits++;try{JSONObject value=new JSONObject(extend);base=value.getString("base");mode=value.getString("mode");}catch(Exception invalid){throw new IllegalArgumentException(invalid);}}
    private JSONObject video(int index)throws Exception{
        return new JSONObject().put("vod_id",String.valueOf(index)).put("vod_name","海边来信"+(index==0?"":" · "+index)).put("vod_pic",base+"/scene.png").put("vod_year","2026").put("vod_content","一封迟到的信，把她带回了海边。沿海公路与灯塔旁，旧日记忆慢慢浮现。");
    }
    private JSONArray items()throws Exception{JSONArray result=new JSONArray();for(int i=0;i<12;i++)result.put(video(i));return result;}
    public String homeContent(boolean filter)throws Exception{
        homes++;initialized=true;Thread.sleep(40);return new JSONObject().put("class",new JSONArray().put(new JSONObject().put("type_id","movie").put("type_name","电影")))
                .put("list",mode.equals("layout")?items():new JSONArray()).toString();
    }
    public String homeVideoContent()throws Exception{
        if(mode.equals("slow-category")&&!initialized)throw new IllegalStateException("homeContent is required");
        Thread.sleep(mode.equals("slow-recommend")?4000:120);return new JSONObject().put("list",items()).toString();
    }
    public String categoryContent(String id,String page,boolean filter,HashMap<String,String> filters)throws Exception{
        if(mode.equals("slow-category")&&!initialized)throw new IllegalStateException("homeContent is required");
        categories++;Thread.sleep(mode.equals("slow-category")?4000:120);return new JSONObject().put("list",items()).put("pagecount",2).toString();
    }
    public String detailContent(List<String> ids)throws Exception{
        details++;JSONObject result=video(0);StringBuilder flags=new StringBuilder(),routes=new StringBuilder();
        for(int i=0;i<24;i++){if(i>0){flags.append("$$$");routes.append("$$$");}flags.append("网盘线路 ").append(i+1);routes.append("[2.48GB] (2026)海边来信.1080p.中文字幕.mkv$").append(base).append("/clip.mp4");}
        result.put("vod_play_from",flags).put("vod_play_url",routes);return new JSONObject().put("list",new JSONArray().put(result)).toString();
    }
    public String playerContent(String flag,String id,List<String> vipFlags)throws Exception{return new JSONObject().put("url",id).put("parse",0).toString();}
    public String action(String action)throws Exception{return new JSONObject().put("categories",categories).put("details",details).put("homes",homes).put("inits",inits).toString();}
}
