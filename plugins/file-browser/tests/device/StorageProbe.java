package xiao.bu.filebrowserprobe;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import dalvik.system.DexClassLoader;
import java.io.*;
import java.util.*;
import org.json.JSONObject;
import xiao.bu.tv.jar.ChannelPlugin;

/** Real runtime permission dialog + system document picker + D-pad navigation. */
public final class StorageProbe extends Probe {
    boolean effectiveRead(){android.app.AppOpsManager ops=(android.app.AppOpsManager)activity.getSystemService(Context.APP_OPS_SERVICE);int mode=ops.checkOpNoThrow(Build.VERSION.SDK_INT>=33?"android:read_media_video":"android:read_external_storage",android.os.Process.myUid(),activity.getPackageName());return activity.checkSelfPermission(Build.VERSION.SDK_INT>=33?"android.permission.READ_MEDIA_VIDEO":android.Manifest.permission.READ_EXTERNAL_STORAGE)==0&&(mode==0||mode==3);}
    void press(String title){ui(()->{Button b=findVisible(root,title);if(b==null||!b.requestFocus())throw new AssertionError("Cannot focus "+title);});waitForIdleSync();sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);waitForIdleSync();}
    Button findVisible(View view,String title){if(view.getVisibility()!=View.VISIBLE)return null;if(view instanceof Button&&title.contentEquals(((Button)view).getText()))return (Button)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){Button found=findVisible(group.getChildAt(i),title);if(found!=null)return found;}}return null;}
    void remoteSystemButton(String... ids)throws Exception{
        getUiAutomation().waitForIdle(500,5000);final AccessibilityNodeInfo[] found={null};
        waitFor("system button "+Arrays.toString(ids),()->{AccessibilityNodeInfo tree=getUiAutomation().getRootInActiveWindow();if(tree==null)return false;try{for(String id:ids){List<AccessibilityNodeInfo> nodes=tree.findAccessibilityNodeInfosByViewId(id);for(AccessibilityNodeInfo node:nodes)if(node.isVisibleToUser()&&node.isEnabled()){found[0]=node;return true;}}return false;}finally{tree.recycle();}});
        check(found[0].performAction(AccessibilityNodeInfo.ACTION_FOCUS),"system control accepts remote focus");Thread.sleep(300);found[0].performAction(AccessibilityNodeInfo.ACTION_FOCUS);found[0].recycle();
        long now=SystemClock.uptimeMillis();getUiAutomation().injectInputEvent(new KeyEvent(now,now,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_CENTER,0),true);getUiAutomation().injectInputEvent(new KeyEvent(now,now+20,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_CENTER,0),true);
    }
    void dialog(boolean allow)throws Exception{remoteSystemButton("com.android.packageinstaller:id/permission_"+(allow?"allow":"deny")+"_button","com.google.android.packageinstaller:id/permission_"+(allow?"allow":"deny")+"_button","com.android.permissioncontroller:id/permission_"+(allow?"allow":"deny")+"_button","com.miui.packageinstaller:id/permission_"+(allow?"allow":"deny")+"_button");}
    void overlayFocus()throws Exception{View panel=(View)field(plugin,"accessShell");check(panel!=null&&panel.hasFocus(),"authorization panel owns focus");ui(()->{ArrayList<View> focusable=new ArrayList<>();root.addFocusables(focusable,View.FOCUS_FORWARD);for(View v:focusable){View walk=v;boolean inside=false;while(walk!=null){if(walk==panel){inside=true;break;}walk=walk.getParent() instanceof View?(View)walk.getParent():null;}if(!inside)throw new AssertionError("Focus escaped to background");}});log("CAPTURE access");Thread.sleep(1500);}
    @Override public void onStart(){int result=Activity.RESULT_CANCELED;
        try{
            activity=startActivitySync(new Intent(getTargetContext(),ProbeActivity.class).putExtra("fixture",args.getString("fixture")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            File jar=new File(activity.getFilesDir(),"file-browser.jar");if(jar.exists())jar.delete();
            try(InputStream input=activity.getAssets().open("file-browser.jar");OutputStream output=new FileOutputStream(jar)){byte[] b=new byte[32768];int n;while((n=input.read(b))!=-1)output.write(b,0,n);}jar.setReadOnly();
            loader=new DexClassLoader(jar.getPath(),activity.getDir("dex",0).getPath(),null,getClass().getClassLoader());plugin=(ChannelPlugin)loader.loadClass("xiao.bu.channel.files.FileBrowserPlugin").newInstance();
            ui(()->{try{root=plugin.create(activity,loader,new JSONObject(),new ChannelPlugin.DirectoryHost(){public void play(String url){}public void status(String text){}public void configureBrowser(android.webkit.WebView view){}
                public void chooseFiles(android.webkit.ValueCallback<Uri[]> callback,String accept,boolean multiple){callback.onReceiveValue(null);}
                public void chooseDirectory(android.webkit.ValueCallback<Uri[]> callback){((ProbeActivity)activity).chooseTree(callback);}
            });activity.setContentView(root);watchVideoCreation();}catch(Exception e){throw new RuntimeException(e);}});
            check(Build.VERSION.SDK_INT>=23,"Android "+Build.VERSION.RELEASE+" / API "+Build.VERSION.SDK_INT);loaded();
            check(!effectiveRead(),"effective storage permission initially denied");
            if("restore".equals(args.getString("phase"))||"show".equals(args.getString("phase"))){
                waitFor("persisted directory",()->list().getCount()>0);final int[] saved={-1};
                ui(()->{try{for(int i=0;i<list().getCount();i++){Object item=list().getAdapter().getItem(i);if(((Integer)field(item,"kind"))==1){saved[0]=i;break;}}if(saved[0]<0)throw new AssertionError("Saved grant missing");list().requestFocusFromTouch();list().setSelection(saved[0]);}catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);loaded();
                check(((Integer)field(field(plugin,"current"),"kind"))==1,"persisted directory reopens without broad storage permission");if("show".equals(args.getString("phase"))){log("CAPTURE directory");Thread.sleep(15000);result=Activity.RESULT_OK;return;}row("sample.mp4");playback("saf-restored");back();result=Activity.RESULT_OK;return;
            }
            boolean runtimeDenied=activity.checkSelfPermission(Build.VERSION.SDK_INT>=33?"android.permission.READ_MEDIA_VIDEO":android.Manifest.permission.READ_EXTERNAL_STORAGE)!=0;
            row("本机存储");if(runtimeDenied)dialog(false);else log("LIMIT emulator treats storage as install-granted; testing AppOps denial/settings recovery instead of runtime dialog");
            waitFor("permission denial return",()->!((Boolean)field(plugin,"storagePermissionPending")));
            check(list().getCount()==0,"denial does not expose files");press("文件授权");overlayFocus();
            if(runtimeDenied){press("允许读取视频");dialog(true);}else{press("应用权限设置");waitFor("settings foreground",()->{AccessibilityNodeInfo n=getUiAutomation().getRootInActiveWindow();if(n==null)return false;try{return "com.android.settings".contentEquals(n.getPackageName());}finally{n.recycle();}});log("WAIT_APP_OP_ALLOW");waitFor("test app permission enabled",()->effectiveRead());long now=SystemClock.uptimeMillis();getUiAutomation().injectInputEvent(new KeyEvent(now,now,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BACK,0),true);getUiAutomation().injectInputEvent(new KeyEvent(now,now+20,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_BACK,0),true);}
            waitFor("app focus after settings",()->root.hasWindowFocus());waitFor("permission recheck",()->!((Boolean)field(plugin,"storagePermissionNeeded"))&&!message().contains("正在读取"));
            if(Environment.getExternalStorageDirectory().canRead()){
                check(list().getCount()>0,"grant automatically resumes browsing");if(Build.VERSION.SDK_INT>=30){row("Movies");row(args.getString("fixture"));}else{row("Android");row("data");row("xiao.bu.filebrowserprobe");row("files");}loaded();
            }else{check(message().contains("目录不可读"),"unmounted shared storage gives actionable error");log("LIMIT emulator reports permission allowed but shared storage remains unreadable; continuing through system directory authorization");}
            press("文件授权");overlayFocus();press("选择目录");
            remoteSystemButton("com.android.documentsui:id/action_menu_select","com.google.android.documentsui:id/action_menu_select","android:id/button1");
            // Some ROMs ask for a second confirmation, others immediately return the URI.
            long confirmEnd=SystemClock.uptimeMillis()+2500;while(SystemClock.uptimeMillis()<confirmEnd){if(((View)field(plugin,"libraryShell")).hasWindowFocus())break;Thread.sleep(100);}
            waitFor("authorized folder opens",()->field(plugin,"current")!=null&&((Integer)field(field(plugin,"current"),"kind"))==1&&message().contains("项"));
            waitFor("picker closed",()->root.hasWindowFocus());getUiAutomation().waitForIdle(500,5000);Thread.sleep(700);log("CAPTURE authorized");Thread.sleep(1500);check(!activity.getContentResolver().getPersistedUriPermissions().isEmpty(),"directory read permission persisted by system");
            check(new org.json.JSONArray(activity.getSharedPreferences("file-browser",0).getString("trees","[]")).length()>0,"authorized directory remembered");
            int parentCount=list().getCount();row("subfolder");loaded();back();loaded();check(list().getCount()==parentCount,"back restores parent directory");
            int expected=list().getSelectedItemPosition();check(list().getAdapter().getItem(expected).toString().endsWith("subfolder"),"back restores selected card");
            ui(()->{try{list().requestFocusFromTouch();list().setSelection(0);}catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);check(((Button[])field(plugin,"browserTabs"))[0].hasFocus(),"left edge enters sidebar");sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);check(list().hasFocus(),"right returns to grid");
            row("sample.mp4");playback("saf");
            ui(()->root.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MENU)));check(((View)field(plugin,"controller")).hasFocus(),"MENU focuses player controls");
            boolean playing=video().isPlaying();sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);check(video().isPlaying()!=playing,"OK activates focused playback button");
            back();check(video()==null,"back releases selected video");result=Activity.RESULT_OK;
        }catch(Throwable error){log("FAIL "+android.util.Log.getStackTraceString(error));}
        finally{ui(()->{if(plugin!=null)plugin.dispose();if(activity!=null)activity.finish();});Bundle end=new Bundle();end.putString("stream",report.toString()+"STORAGE_RESULT="+(result==Activity.RESULT_OK?"PASS":"FAIL")+"\n");finish(result,end);}
    }
}
