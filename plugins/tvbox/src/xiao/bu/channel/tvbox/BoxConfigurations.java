package xiao.bu.channel.tvbox;

import android.content.*;
import java.io.IOException;
import java.util.*;
import org.json.*;

/** Saved configuration addresses are independent of cloud authorization preferences. */
final class BoxConfigurations {
    private final SharedPreferences preferences;
    BoxConfigurations(Context context){preferences=context.getSharedPreferences("tvbox-configurations",Context.MODE_PRIVATE);}
    List<String> addresses(){
        List<String> result=new ArrayList<>();try{JSONArray saved=new JSONArray(preferences.getString("addresses","[]"));for(int i=0;i<Math.min(50,saved.length());i++){String url=saved.optString(i);if(!url.isEmpty()&&!result.contains(url))result.add(url);}}catch(JSONException ignored){}return result;
    }
    String resolve(String supplied){
        supplied=supplied.trim();String active=active();if(supplied.isEmpty())return active;
        try{supplied=BoxHttp.url(supplied).toString();}catch(IOException invalid){return supplied;}
        String previous=preferences.getString("entry","");preferences.edit().putString("entry",supplied).apply();
        return supplied.equals(previous)&&!active.isEmpty()?active:supplied;
    }
    String active(){return preferences.getString("active","");}
    String remember(String value)throws IOException{
        String url=BoxHttp.url(value.trim()).toString();List<String> saved=addresses();
        if(!saved.contains(url)){if(saved.size()>=50)throw new IOException("最多保存 50 个配置地址");saved.add(url);}
        preferences.edit().putString("addresses",new JSONArray(saved).toString()).apply();return url;
    }
    void activate(String value){try{String url=remember(value);preferences.edit().putString("active",url).apply();}catch(IOException ignored){}}
    void remove(String url){List<String> saved=addresses();saved.remove(url);SharedPreferences.Editor editor=preferences.edit().putString("addresses",new JSONArray(saved).toString());if(url.equals(active()))editor.remove("active");editor.apply();}
}
