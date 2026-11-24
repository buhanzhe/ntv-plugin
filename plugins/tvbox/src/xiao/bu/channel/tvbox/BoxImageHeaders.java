package xiao.bu.channel.tvbox;

import java.net.URLDecoder;
import java.util.regex.Pattern;
import org.json.JSONObject;

/** TVBox image suffixes support @Header=, pipe/form headers and encoded JSON. */
final class BoxImageHeaders {
    private static final String TOKEN="[A-Za-z0-9!#$%&'*+.^_`|~-]+";
    private static final Pattern START=Pattern.compile("(?i)@(?=(?:Referer|User-Agent|Cookie|Origin|Authorization|Accept)=|\\{|%7b)");
    private static final Pattern NEXT=Pattern.compile("(?i)@(?="+TOKEN+"=)");
    static int offset(String url){int pipe=url.indexOf('|');if(pipe>=0)return pipe;java.util.regex.Matcher matcher=START.matcher(url);return matcher.find()?matcher.start():-1;}
    static JSONObject parse(String url)throws Exception{
        int offset=offset(url);JSONObject result=new JSONObject();if(offset<0)return result;
        String raw=url.substring(offset+1),decoded=URLDecoder.decode(raw.replace("+","%2B"),"UTF-8");
        if(decoded.startsWith("{"))return new JSONObject(decoded);
        boolean at=NEXT.matcher(decoded).find();
        for(String field:(at?decoded:raw).split(at?NEXT.pattern():"&")){
            int equal=field.indexOf('=');if(equal<=0)continue;
            String name=at?field.substring(0,equal):URLDecoder.decode(field.substring(0,equal),"UTF-8");
            String value=at?field.substring(equal+1):URLDecoder.decode(field.substring(equal+1),"UTF-8");
            if(name.matches(TOKEN)&&value.indexOf('\r')<0&&value.indexOf('\n')<0)result.put(name,value);
        }
        return result;
    }
}
