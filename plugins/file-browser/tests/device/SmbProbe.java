package xiao.bu.filebrowserprobe;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import dalvik.system.DexClassLoader;
import java.io.*;
import java.net.*;
import java.util.*;
import org.json.JSONObject;
import xiao.bu.tv.jar.ChannelPlugin;

/** Runs actual delivered DEX and native playback against independently configured servers. */
public final class SmbProbe extends Probe {
    void connect(String address,String user,String password,boolean automatic)throws Exception {
        ui(()->{try{
            java.lang.reflect.Method method=plugin.getClass().getDeclaredMethod("connectForm",String.class,boolean.class);method.setAccessible(true);method.invoke(plugin,address,automatic);
            if(!automatic){LinearLayout form=(LinearLayout)field(plugin,"form");ArrayList<EditText> inputs=new ArrayList<>();
                for(int i=0;i<form.getChildCount();i++)if(form.getChildAt(i) instanceof EditText)inputs.add((EditText)form.getChildAt(i));
                inputs.get(0).setText(address);inputs.get(1).setText(user);inputs.get(2).setText(password);inputs.get(3).setText("WORKGROUP");
                ((android.view.inputmethod.InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(root.getWindowToken(),0);Button submit=button(form,"连接");if(!submit.requestFocusFromTouch())throw new AssertionError("Connect button focus");
            }
        }catch(Exception error){throw new RuntimeException(error);}});
        if(!automatic){waitForIdleSync();Thread.sleep(150);sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);}
        if(Build.VERSION.SDK_INT>=37&&activity.getApplicationInfo().targetSdkVersion>=37&&activity.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK")!=0){if(Boolean.parseBoolean(args.getString("denyNetwork"))){boolean denied=SystemUi.pressUnless(this,()->activity.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK")==0,"com.android.permissioncontroller:id/permission_deny_button");if(denied){waitFor("network denial return",()->!((Boolean)field(plugin,"networkPermissionPending")));check(field(plugin,"smb")==null&&field(plugin,"form")!=null,"denied local network permission keeps recoverable form");ui(()->button(root,"连接").requestFocusFromTouch());waitForIdleSync();sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);}}
            SystemUi.pressUnless(this,()->activity.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK")==0,"com.android.permissioncontroller:id/permission_allow_button");waitFor("local network permission",()->activity.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK")==0);check(true,"plugin requested native local network permission");}
    }
    void connected()throws Exception{waitFor("SMB connection",()->{if(message().contains("失败"))throw new AssertionError(message());return field(plugin,"form")==null;});loaded();}
    void range(String url)throws Exception{
        HttpURLConnection http=(HttpURLConnection)new URL(url).openConnection();http.setConnectTimeout(10000);http.setReadTimeout(10000);http.setRequestProperty("Range","bytes=1048576-1052671");
        try{check(http.getResponseCode()==206,"SMB HTTP byte range supported");ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=http.getInputStream()){byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1)bytes.write(buffer,0,n);}
            byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());StringBuilder hex=new StringBuilder();for(byte b:digest)hex.append(String.format(java.util.Locale.US,"%02x",b&255));
            check(bytes.size()==4096&&hex.toString().equals(args.getString("rangeHash")),"SMB seek returns exact fixture bytes");
        }finally{http.disconnect();}
    }
    @Override public void onStart(){int result=Activity.RESULT_CANCELED;
        try{
            activity=startActivitySync(new Intent(getTargetContext(),ProbeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));log("ACTIVITY_READY");if(Build.VERSION.SDK_INT>=29){if(!activity.hasWindowFocus())log("WAIT_FOREGROUND");waitFor("probe activity foreground",()->activity.hasWindowFocus());}
            File jar=new File(activity.getFilesDir(),"file-browser.jar");if(jar.exists())jar.delete();
            try(InputStream in=pluginInput();OutputStream out=new FileOutputStream(jar)){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}jar.setReadOnly();verifyJar(jar);
            loader=new DexClassLoader(jar.getPath(),activity.getDir("dex",0).getPath(),null,getClass().getClassLoader());plugin=(ChannelPlugin)loader.loadClass("xiao.bu.channel.files.FileBrowserPlugin").newInstance();
            ui(()->{try{root=plugin.create(activity,loader,new JSONObject(),new ChannelPlugin.DirectoryHost(){public void play(String u){}public void status(String t){}public void configureBrowser(android.webkit.WebView v){}public void chooseFiles(android.webkit.ValueCallback<Uri[]> c,String t,boolean m){c.onReceiveValue(null);}public void chooseDirectory(android.webkit.ValueCallback<Uri[]> c){c.onReceiveValue(null);}});activity.setContentView(root);watchVideoCreation();}catch(Exception e){throw new RuntimeException(e);}});
            loaded();String address=args.getString("smb"),user=args.getString("user","");boolean negative=Boolean.parseBoolean(args.getString("negative"));
            log("SERVER "+args.getString("label")+" API "+Build.VERSION.SDK_INT);
            if(!user.isEmpty()&&!negative){connect(address,user,"intentionally-wrong",false);waitFor("incorrect password rejected",()->message().contains("失败"));check(field(plugin,"form")!=null,"wrong password keeps editable connection form");}
            connect(address,user,user.isEmpty()?"":"test-password",user.isEmpty());
            if(negative){waitFor("unsupported server rejected",()->message().contains("失败"));check(field(plugin,"form")!=null&&((View)field(plugin,"libraryShell")).getVisibility()==View.GONE,"unsupported protocol fails without escaping modal");back();check(field(plugin,"form")==null,"remote BACK recovers from failed connection");result=Activity.RESULT_OK;return;}
            connected();check(true,user.isEmpty()?"password-free entry opens automatically":"correct credentials recover after rejected password");
            if(Boolean.parseBoolean(args.getString("enumerate"))){row("媒体 videos/");loaded();check(true,"server root enumerates Unicode share");}
            row("子目录 中文/");loaded();row("电影 sample.mp4");playback("smb");
            Object proxy=field(plugin,"proxy");String url=(String)proxy.getClass().getMethod("url").invoke(proxy);range(url);
            back();check(video()==null&&field(plugin,"proxy")==null,"remote BACK releases player and proxy");
            try{new Socket("127.0.0.1",new URL(url).getPort()).close();throw new AssertionError("Proxy still listening");}catch(ConnectException expected){check(true,"proxy socket closed");}
            loaded();check(!activity.getSharedPreferences("file-browser",0).getAll().containsValue("test-password"),"password never persisted");result=Activity.RESULT_OK;
        }catch(Throwable error){log("FAIL "+android.util.Log.getStackTraceString(error));
            try{Object session=loader.loadClass("xiao.bu.channel.files.SmbSession").getConstructor(String.class,String.class,String.class,String.class).newInstance(args.getString("smb"),"WORKGROUP",args.getString("user",""),args.getString("user","").isEmpty()?"":"test-password");
                try{session.getClass().getMethod("list",String.class).invoke(session,args.getString("smb"));}finally{session.getClass().getMethod("close").invoke(session);}
            }catch(Throwable detail){log("SMB_DIAGNOSTIC "+android.util.Log.getStackTraceString(detail));}
        }
        finally{ui(()->{if(plugin!=null)plugin.dispose();if(activity!=null)activity.finish();});Bundle end=new Bundle();end.putString("stream",report+"SMB_RESULT="+(result==Activity.RESULT_OK?"PASS":"FAIL")+"\n");finish(result,end);}
    }
}
