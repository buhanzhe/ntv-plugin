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
public class Probe extends Instrumentation {
    Bundle args; Activity activity; ChannelPlugin plugin; View root; ClassLoader loader;
    StringBuilder report = new StringBuilder();
    boolean cpuOverlayPaused;
    volatile boolean rendered;volatile MediaPlayer observedPlayer;
    void watchVideoCreation(){((ViewGroup)root).setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener(){
        public void onChildViewAdded(View parent,View child){observeVideo(child);}
        public void onChildViewRemoved(View parent,View child){}
    });}
    void observeVideo(View view){if(view instanceof VideoView&&Build.VERSION.SDK_INT>=17){rendered=false;observedPlayer=null;((VideoView)view).setOnInfoListener((player,what,extra)->{observedPlayer=player;if(what==3)rendered=true;return false;});}
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)observeVideo(group.getChildAt(i));}}
    InputStream pluginInput()throws IOException{return Boolean.parseBoolean(args.getString("externalJar"))?new FileInputStream(new File(activity.getFilesDir(),"plugin-under-test.jar")):activity.getAssets().open("file-browser.jar");}
    void verifyJar(File jar)throws Exception{String expected=args.getString("expectedHash");if(expected==null)return;java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(jar)){byte[] buffer=new byte[32768];int n;while((n=in.read(buffer))!=-1)digest.update(buffer,0,n);}StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(java.util.Locale.US,"%02x",b&255));check(expected.equals(hex.toString()),"delivered JAR SHA-256 "+hex);}
    final ComponentName cpuOverlay=new ComponentName("com.android.systemui","com.android.systemui.LoadAverageService");
    interface Read { boolean ready() throws Exception; }
    public void onCreate(Bundle arguments) { args=arguments; super.onCreate(arguments); start(); }
    void check(boolean ok,String text) { if(!ok)throw new AssertionError(text); log("PASS "+text); }
    void log(String text) { report.append(text).append('\n'); Bundle b=new Bundle();b.putString("stream",text+"\n");sendStatus(0,b); }
    Object field(Object object,String name)throws Exception { Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object); }
    void ui(Runnable action) { final Throwable[] error={null};runOnMainSync(()->{try{action.run();}catch(Throwable failure){error[0]=failure;}});if(error[0]!=null)throw new AssertionError("UI probe failed: "+error[0]); }
    void waitFor(String label,Read read)throws Exception { long end=SystemClock.elapsedRealtime()+25000;while(SystemClock.elapsedRealtime()<end){if(read.ready())return;Thread.sleep(150);}throw new AssertionError("Timeout: "+label); }
    Button button(View view,String text) {
        if(view instanceof Button && ((Button)view).getText().toString().equals(text))return (Button)view;
        if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++){Button b=button(g.getChildAt(i),text);if(b!=null)return b;}}
        return null;
    }
    void click(String title) { ui(()->{Button b=button(root,title);if(b==null)throw new AssertionError("Missing button "+title);b.performClick();}); }
    GridView list()throws Exception{return (GridView)field(plugin,"list");}
    String message()throws Exception{return ((TextView)field(plugin,"status")).getText().toString();}
    void loaded()throws Exception {final String[] state={"",""};waitFor("directory loaded",()->{ui(()->{try{state[0]=message();state[1]=((TextView)field(plugin,"path")).getText().toString();}catch(Exception e){throw new RuntimeException(e);}});return !state[0].contains("正在读取");});check(!state[0].contains("失败"),"directory loads: "+state[1]+" / "+state[0]);}
    void row(String title)throws Exception {
        loaded(); final GridView list=list(); final int[] index={-1};
        ui(()->{for(int i=0;i<list.getCount();i++)if(list.getAdapter().getItem(i).toString().toLowerCase(java.util.Locale.US).endsWith(title.toLowerCase(java.util.Locale.US))){index[0]=i;break;}
            if(index[0]<0)throw new AssertionError("Missing row "+title);list.requestFocusFromTouch();list.setSelection(index[0]);});
        waitForIdleSync();Thread.sleep(200);
        sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);waitForIdleSync();
    }
    VideoView video()throws Exception{return (VideoView)field(plugin,"video");}
    int position()throws Exception{final int[] p={0};ui(()->{try{p[0]=video().getCurrentPosition();}catch(Exception e){throw new RuntimeException(e);}});return p[0];}
    void playback(String name)throws Exception {
        waitFor(name+" prepare",()->{final boolean[] ready={false};ui(()->{try{VideoView v=video();ready[0]=v!=null&&v.isPlaying()&&v.getDuration()>0;}catch(Exception e){throw new RuntimeException(e);}});return ready[0];});
        VideoView v=video();MediaPlayer player;if(Build.VERSION.SDK_INT>=17){waitFor("renderer callback",()->observedPlayer!=null);player=observedPlayer;}else{Field media=VideoView.class.getDeclaredField("mMediaPlayer");media.setAccessible(true);player=(MediaPlayer)media.get(v);}
        Object controls=field(plugin,"controller");check(controls.getClass().getSimpleName().equals("VideoControlsOverlay"),name+" reuses web-TV playback controls");
        check(player.getVideoWidth()>0&&player.getVideoHeight()>0,name+" decoded dimensions "+player.getVideoWidth()+"x"+player.getVideoHeight());
        int before=position();Thread.sleep(2200);int after=position();check(after>before+1000,name+" clock advances "+before+" -> "+after);
        if(Build.VERSION.SDK_INT>=17){waitFor("native first frame",()->rendered);check(true,name+" native renderer reported first frame");}
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
    void performance()throws Exception {
        File directory=new File(activity.getExternalFilesDir(null),"subfolder");directory.mkdirs();
        for(int i=0;i<600;i++)new File(directory,String.format(java.util.Locale.US,"clip-%03d.mp4",i)).createNewFile();
        row("subfolder");loaded();check(list().getCount()==600,"performance directory contains 600 entries");
        final ArrayList<Long> heartbeat=new ArrayList<>();final boolean[] running={true};
        final Handler handler=new Handler(Looper.getMainLooper());
        final Runnable pulse=new Runnable(){long last=SystemClock.uptimeMillis();public void run(){long now=SystemClock.uptimeMillis();if(running[0]){heartbeat.add(now-last);last=now;handler.postDelayed(this,16);}}};
        ui(()->handler.post(pulse));
        for(int i=0;i<20;i++){final int index=i*30;ui(()->{try{list().setSelection(index);}catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();Thread.sleep(32);}
        ui(()->{running[0]=false;handler.removeCallbacks(pulse);});
        Collections.sort(heartbeat);if(!heartbeat.isEmpty())log("PERF main_queue_p95_ms="+heartbeat.get(Math.min(heartbeat.size()-1,(int)(heartbeat.size()*.95)))+" samples="+heartbeat.size());
        final long[] draw=new long[24];final int[] children={0};
        ui(()->{try{
            children[0]=list().getChildCount();android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(root.getWidth(),root.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas=new android.graphics.Canvas(bitmap);for(int i=0;i<28;i++){long start=System.nanoTime();root.draw(canvas);if(i>=4)draw[i-4]=System.nanoTime()-start;}bitmap.recycle();
        }catch(Exception e){throw new RuntimeException(e);}});
        Arrays.sort(draw);android.os.Debug.MemoryInfo memory=new android.os.Debug.MemoryInfo();android.os.Debug.getMemoryInfo(memory);
        check(children[0]<24,"grid only instantiates visible cards: "+children[0]);
        log("PERF software_draw_p95_ms="+(draw[22]/1000000.0)+" process_pss_kib="+memory.getTotalPss()+" java_heap_kib="+((Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())/1024));
        back();loaded();
    }
    public void onStart(){int result=Activity.RESULT_CANCELED;
        try {
            activity=startActivitySync(new Intent(getTargetContext(),ProbeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));log("ACTIVITY_READY");if(Build.VERSION.SDK_INT>=29){if(!activity.hasWindowFocus())log("WAIT_FOREGROUND");waitFor("probe activity foreground",()->activity.hasWindowFocus());}
            try{cpuOverlayPaused=activity.stopService(new Intent().setComponent(cpuOverlay));}catch(SecurityException ignored){}
            File jar=new File(activity.getFilesDir(),"file-browser.jar");
            if(jar.exists()&&!jar.delete())throw new IOException("Cannot replace probe-owned JAR");
            try(InputStream in=pluginInput();OutputStream out=new FileOutputStream(jar)){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
            jar.setReadOnly();verifyJar(jar);File dex=activity.getDir("dex",0);loader=new DexClassLoader(jar.getPath(),dex.getPath(),null,getClass().getClassLoader());
            plugin=(ChannelPlugin)loader.loadClass("xiao.bu.channel.files.FileBrowserPlugin").newInstance();
            ui(()->{try{root=plugin.create(activity,loader,new JSONObject(),new ChannelPlugin.DirectoryHost(){
                public void play(String url){}public void status(String text){}public void configureBrowser(android.webkit.WebView web){}
                public void chooseFiles(android.webkit.ValueCallback<Uri[]> callback,String type,boolean multiple){callback.onReceiveValue(new Uri[]{Uri.fromFile(new File(activity.getExternalFilesDir(null),"sample.mp4"))});}
                public void chooseDirectory(android.webkit.ValueCallback<Uri[]> callback){callback.onReceiveValue(null);}
            });activity.setContentView(root);watchVideoCreation();}catch(Exception e){throw new RuntimeException(e);}});
            check(Build.VERSION.SDK_INT>=14,"Android "+Build.VERSION.RELEASE+" / API "+Build.VERSION.SDK_INT);loaded();
            if(Boolean.parseBoolean(args.getString("screenshots"))){
                log("CAPTURE browser");Thread.sleep(2000);
                row("本机存储");row("Android");row("data");row("xiao.bu.filebrowserprobe");row("files");loaded();log("CAPTURE media");Thread.sleep(2000);
                click("外接存储");loaded();log("CAPTURE external");Thread.sleep(2000);click("SMB");
                waitFor("scan screenshots",()->{LinearLayout scan=(LinearLayout)field(plugin,"discovery");String text=((TextView)scan.findViewWithTag("scan-status")).getText().toString();return text.contains("扫描完成")||text.contains("未发现");});
                log("CAPTURE discovery");Thread.sleep(2000);click("手动连接");
                ui(()->((android.view.inputmethod.InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(root.getWindowToken(),0));
                log("CAPTURE connection");Thread.sleep(2000);result=Activity.RESULT_OK;return;
            }
            check(button(root,"选择目录")==null,"modern directory picker hidden on old Android");
            row("本机存储");row("Android");row("data");row("xiao.bu.filebrowserprobe");row("files");loaded();
            check(list().getCount()==2,"local video filtering and subdirectory (text file hidden)");
            row("sample.mp4");playback("local");back();check(video()==null,"local return releases player");
            if(Boolean.parseBoolean(args.getString("perf")))performance();
            boolean skipScan=Boolean.parseBoolean(args.getString("skipScan"));
            if(skipScan){ui(()->{try{Method method=plugin.getClass().getDeclaredMethod("connectForm",String.class,boolean.class);method.setAccessible(true);method.invoke(plugin,args.getString("smb"),false);}catch(Exception e){throw new RuntimeException(e);}});}else{
            click("SMB");
            waitFor("LAN scan",()->{LinearLayout scan=(LinearLayout)field(plugin,"discovery");return scan!=null&&((TextView)scan.findViewWithTag("scan-status")).getText().toString().contains("扫描完成");});
            ui(()->{try{LinearLayout scan=(LinearLayout)field(plugin,"discovery");GridView devices=(GridView)scan.findViewWithTag("scan-devices");
                int found=-1;for(int i=0;i<devices.getCount();i++)if(devices.getAdapter().getItem(i).equals(args.getString("scanHost")))found=i;
                check(found>=0,"automatic LAN discovery found fixture host");
                devices.performItemClick(null,found,found);
            }catch(Exception e){throw new RuntimeException(e);}});
            }
            ui(()->{try{LinearLayout form=(LinearLayout)field(plugin,"form");ArrayList<EditText> inputs=new ArrayList<>();for(int i=0;i<form.getChildCount();i++)if(form.getChildAt(i) instanceof EditText)inputs.add((EditText)form.getChildAt(i));
                if(!skipScan)check(inputs.get(1).getText().length()==0&&inputs.get(2).getText().length()==0,"discovered device automatically tries password-free credentials");
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
        finally{ui(()->{if(plugin!=null)plugin.dispose();if(activity!=null){if(cpuOverlayPaused)try{activity.startService(new Intent().setComponent(cpuOverlay));}catch(SecurityException ignored){}activity.finish();}});Bundle end=new Bundle();end.putString("stream",report.toString()+"FILE_BROWSER_RESULT="+(result==Activity.RESULT_OK?"PASS":"FAIL")+"\n");finish(result,end);}
    }
}
