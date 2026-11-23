package xiao.bu.filebrowserprobe;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import android.media.MediaPlayer;
import dalvik.system.DexClassLoader;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.JSONObject;
import xiao.bu.tv.jar.ChannelPlugin;

/** Runs the delivered DEX, not a recompiled copy of the plugin sources. */
public final class Probe extends Instrumentation {
    Bundle args; Activity activity; ChannelPlugin plugin; View root; ClassLoader loader;
    StringBuilder report = new StringBuilder();
    interface Read { boolean ready() throws Exception; }
    public void onCreate(Bundle arguments) { args=arguments; super.onCreate(arguments); start(); }
    void check(boolean ok,String text) { if(!ok)throw new AssertionError(text); log("PASS "+text); }
    void log(String text) { report.append(text).append('\n'); Bundle b=new Bundle();b.putString("stream",text+"\n");sendStatus(0,b); }
    Object field(Object object,String name)throws Exception { Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object); }
    void ui(Runnable action) { runOnMainSync(action); }
    void waitFor(String label,Read read)throws Exception { long end=SystemClock.elapsedRealtime()+25000;while(SystemClock.elapsedRealtime()<end){if(read.ready())return;Thread.sleep(150);}throw new AssertionError("Timeout: "+label); }
    Button button(View view,String text) {
        if(view instanceof Button && ((Button)view).getText().toString().equals(text))return (Button)view;
        if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++){Button b=button(g.getChildAt(i),text);if(b!=null)return b;}}
        return null;
    }
    void click(String title) { ui(()->{Button b=button(root,title);if(b==null)throw new AssertionError("Missing button "+title);b.performClick();}); }
    ListView list()throws Exception{return (ListView)field(plugin,"list");}
    String message()throws Exception{return ((TextView)field(plugin,"status")).getText().toString();}
    void loaded()throws Exception { waitFor("directory loaded",()->!message().contains("正在读取"));check(!message().contains("失败"),"directory loads: "+((TextView)field(plugin,"path")).getText()+" / "+message()); }
    void row(String title)throws Exception {
        loaded(); final ListView list=list(); final int[] index={-1};
        ui(()->{for(int i=0;i<list.getCount();i++)if(list.getAdapter().getItem(i).toString().endsWith(title)){index[0]=i;break;}
            if(index[0]<0)throw new AssertionError("Missing row "+title);list.requestFocusFromTouch();list.setSelection(index[0]);});
        waitForIdleSync();Thread.sleep(200);
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);waitForIdleSync();
    }
    VideoView video()throws Exception{return (VideoView)field(plugin,"video");}
    int position()throws Exception{final int[] p={0};ui(()->{try{p[0]=video().getCurrentPosition();}catch(Exception e){throw new RuntimeException(e);}});return p[0];}
    void playback(String name)throws Exception {
        waitFor(name+" prepare",()->{final boolean[] ready={false};ui(()->{try{VideoView v=video();ready[0]=v!=null&&v.isPlaying()&&v.getDuration()>0;}catch(Exception e){throw new RuntimeException(e);}});return ready[0];});
        VideoView v=video();Field media=VideoView.class.getDeclaredField("mMediaPlayer");media.setAccessible(true);
        MediaPlayer player=(MediaPlayer)media.get(v);check(player.getVideoWidth()>0&&player.getVideoHeight()>0,name+" decoded dimensions "+player.getVideoWidth()+"x"+player.getVideoHeight());
        int before=position();Thread.sleep(2200);int after=position();check(after>before+1000,name+" clock advances "+before+" -> "+after);
        log("CAPTURE "+name);Thread.sleep(1500);
        // Exercise the plugin's actual remote dispatch, including its popup-controller state.
        ui(()->root.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_CENTER)));
        check(!v.isPlaying(),name+" remote pause");
        ui(()->root.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_CENTER)));
        check(v.isPlaying(),name+" remote resume");
        int previous=position();ui(()->root.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT)));
        waitFor(name+" seek",()->position()>previous+7000);check(true,name+" remote seek +10s");
        ui(()->((ChannelPlugin.NativePage)plugin).setActive(false));check(!v.isPlaying(),name+" background pause");
        ui(()->((ChannelPlugin.NativePage)plugin).setActive(true));waitFor(name+" resume",()->v.isPlaying());
        check(true,name+" foreground resume");
    }
    void back(){ui(()->{if(!((ChannelPlugin.NativePage)plugin).onBack())throw new AssertionError("Back unhandled");});}
    public void onStart(){int result=Activity.RESULT_CANCELED;
        try {
            activity=startActivitySync(new Intent(getTargetContext(),ProbeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            File jar=new File(activity.getFilesDir(),"file-browser.jar");
            if(jar.exists()&&!jar.delete())throw new IOException("Cannot replace probe-owned JAR");
            try(InputStream in=activity.getAssets().open("file-browser.jar");OutputStream out=new FileOutputStream(jar)){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
            jar.setReadOnly();File dex=activity.getDir("dex",0);loader=new DexClassLoader(jar.getPath(),dex.getPath(),null,getClass().getClassLoader());
            plugin=(ChannelPlugin)loader.loadClass("xiao.bu.channel.files.FileBrowserPlugin").newInstance();
            ui(()->{try{root=plugin.create(activity,loader,new JSONObject(),new ChannelPlugin.DirectoryHost(){
                public void play(String url){}public void status(String text){}public void configureBrowser(android.webkit.WebView web){}
                public void chooseFiles(android.webkit.ValueCallback<Uri[]> callback,String type,boolean multiple){callback.onReceiveValue(new Uri[]{Uri.fromFile(new File(activity.getExternalFilesDir(null),"sample.mp4"))});}
                public void chooseDirectory(android.webkit.ValueCallback<Uri[]> callback){callback.onReceiveValue(null);}
            });activity.setContentView(root);}catch(Exception e){throw new RuntimeException(e);}});
            check(Build.VERSION.SDK_INT==19,"Android 4.4 / API 19");loaded();
            if(Boolean.parseBoolean(args.getString("screenshots"))){
                log("CAPTURE browser");Thread.sleep(2000);click("SMB");
                waitFor("scan screenshots",()->{LinearLayout scan=(LinearLayout)field(plugin,"discovery");String text=((TextView)scan.getChildAt(1)).getText().toString();return text.contains("扫描完成")||text.contains("未发现");});
                log("CAPTURE discovery");Thread.sleep(2000);click("手动连接");
                ui(()->((android.view.inputmethod.InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(root.getWindowToken(),0));
                log("CAPTURE connection");Thread.sleep(2000);result=Activity.RESULT_OK;return;
            }
            check(button(root,"选择目录")==null,"API21 directory picker hidden on KitKat");
            row("本机存储");row("Android");row("data");row("xiao.bu.filebrowserprobe");row("files");loaded();
            check(list().getCount()==2,"local video filtering and subdirectory (text file hidden)");
            row("sample.mp4");playback("local");back();check(video()==null,"local return releases player");
            click("SMB");
            waitFor("LAN scan",()->{LinearLayout scan=(LinearLayout)field(plugin,"discovery");return scan!=null&&((TextView)scan.getChildAt(1)).getText().toString().contains("扫描完成");});
            ui(()->{try{LinearLayout scan=(LinearLayout)field(plugin,"discovery");ListView devices=(ListView)scan.getChildAt(3);
                int found=-1;for(int i=0;i<devices.getCount();i++)if(devices.getAdapter().getItem(i).equals(args.getString("scanHost")))found=i;
                check(found>=0,"automatic LAN discovery found fixture host");
                devices.performItemClick(null,found,found);
            }catch(Exception e){throw new RuntimeException(e);}});
            ui(()->{try{LinearLayout form=(LinearLayout)field(plugin,"form");ArrayList<EditText> inputs=new ArrayList<>();for(int i=0;i<form.getChildCount();i++)if(form.getChildAt(i) instanceof EditText)inputs.add((EditText)form.getChildAt(i));
                check(inputs.get(1).getText().length()==0&&inputs.get(2).getText().length()==0,"discovered device automatically tries password-free credentials");
                boolean guest=Boolean.parseBoolean(args.getString("guest"));
                inputs.get(0).setText(args.getString("smb"));inputs.get(1).setText(guest?"":"viewer");inputs.get(2).setText(guest?"":"test-password");
            }catch(Exception e){throw new RuntimeException(e);}});
            click("连接");waitFor("SMB connection",()->{java.util.concurrent.Future<?> job=(java.util.concurrent.Future<?>)field(plugin,"pending");if(job.isDone())job.get();if(message().contains("失败"))throw new AssertionError(message());return field(plugin,"form")==null;});check(true,"SMB connection form accepted");loaded();row("电影 sample.mp4");playback("smb");
            Object proxy=field(plugin,"proxy");String proxyUrl=(String)proxy.getClass().getMethod("url").invoke(proxy);
            back();check(video()==null&&field(plugin,"proxy")==null,"SMB return releases player and proxy");
            try{new java.net.Socket("127.0.0.1",new java.net.URL(proxyUrl).getPort()).close();throw new AssertionError("proxy port still open");}catch(java.net.ConnectException expected){check(true,"SMB proxy port closed");}
            loaded();check(!activity.getSharedPreferences("file-browser",0).getAll().containsValue("test-password"),"SMB password not persisted");
            check(true,"delivered JAR loaded through DexClassLoader");result=Activity.RESULT_OK;
        }catch(Throwable error){log("FAIL "+android.util.Log.getStackTraceString(error));
            try{if(loader!=null&&plugin!=null&&field(plugin,"form")!=null){
                Object session=loader.loadClass("xiao.bu.channel.files.SmbSession").getConstructor(String.class,String.class,String.class,String.class).newInstance(args.getString("smb"),"","viewer","test-password");
                try{session.getClass().getMethod("list",String.class).invoke(session,args.getString("smb"));}finally{session.getClass().getMethod("close").invoke(session);}
            }}catch(Throwable detail){log("SMB_DIAGNOSTIC "+android.util.Log.getStackTraceString(detail));}
        }
        finally{ui(()->{if(plugin!=null)plugin.dispose();if(activity!=null)activity.finish();});Bundle end=new Bundle();end.putString("stream",report.toString()+"FILE_BROWSER_RESULT="+(result==Activity.RESULT_OK?"PASS":"FAIL")+"\n");finish(result,end);}
    }
}
