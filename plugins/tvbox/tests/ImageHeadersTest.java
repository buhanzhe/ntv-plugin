package xiao.bu.channel.tvbox;
import org.json.JSONObject;
public final class ImageHeadersTest {
    private static void check(boolean valid,String name){if(!valid)throw new AssertionError(name);}
    public static void main(String[] args)throws Exception{
        String base="https://img3.doubanio.com/view/a.jpg";
        JSONObject at=BoxImageHeaders.parse(base+"@Referer=https://api.douban.com/@User-Agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
        check(at.length()==2&&at.getString("Referer").equals("https://api.douban.com/")&&at.getString("User-Agent").startsWith("Mozilla/"),"separate TVBox @ headers");
        JSONObject form=BoxImageHeaders.parse(base+"|Referer=https%3A%2F%2Fexample.test%2F%3Fa%3D1%26b%3D2&User-Agent=TV+Box");
        check(form.getString("Referer").endsWith("?a=1&b=2")&&form.getString("User-Agent").equals("TV Box"),"form encoded values");
        JSONObject json=BoxImageHeaders.parse(base+"@%7B%22Referer%22%3A%22https%3A%2F%2Fexample.test%2F%22%2C%22User-Agent%22%3A%22A%2BB%22%7D");
        check(json.getString("User-Agent").equals("A+B"),"JSON retains plus");
        check(BoxImageHeaders.offset(base)==-1&&BoxImageHeaders.parse(base).length()==0,"plain image address");
        check(!BoxImageHeaders.parse(base+"|Referer=ok%0D%0AX-Test%3Avalue").has("Referer"),"reject header line injection");
        System.out.println("PASS image header suffixes: @, form, encoded JSON, plus signs and line injection");
    }
}
