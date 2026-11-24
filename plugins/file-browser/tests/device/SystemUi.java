package xiao.bu.filebrowserprobe;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.*;
import java.util.*;
/** Test-only adapter for OEM permission windows and system document pickers. */
final class SystemUi {
 static boolean trusted(String pkg){return pkg.contains("permissioncontroller")||pkg.contains("documentsui")||pkg.contains("packageinstaller")||pkg.equals("com.lbe.security.miui")||pkg.equals("com.miui.securitycenter");}
 static List<AccessibilityNodeInfo> roots(android.app.UiAutomation automation){
  List<AccessibilityNodeInfo> roots=new ArrayList<>();List<AccessibilityWindowInfo> windows=new ArrayList<>(automation.getWindows());
  Collections.sort(windows,(a,b)->b.getLayer()-a.getLayer());
  for(AccessibilityWindowInfo window:windows){AccessibilityNodeInfo root=window.getRoot();if(root!=null)roots.add(root);window.recycle();}
  if(roots.isEmpty()){AccessibilityNodeInfo root=automation.getRootInActiveWindow();if(root!=null)roots.add(root);}return roots;
 }
 static AccessibilityNodeInfo find(android.app.UiAutomation automation,String[] ids){
  String joined=Arrays.toString(ids);boolean permission=joined.contains("permission_");
  String[] labels=joined.contains("permission_deny")?new String[]{"拒绝","不允许","Deny","Don't allow"}:joined.contains("permission_allow")?new String[]{"允许访问全部","允许所有视频","允许全部","仅在使用该应用时允许","仅在使用中允许","始终允许","允许","Allow all","While using the app","Allow"}:new String[]{"使用此文件夹","选择此文件夹","选择","允许","Use this folder","Select","Allow"};
  List<AccessibilityNodeInfo> roots=roots(automation);
  try{for(AccessibilityNodeInfo root:roots){String pkg=String.valueOf(root.getPackageName());if(!trusted(pkg))continue;
   if(permission&&root.findAccessibilityNodeInfosByText("File browser probe").isEmpty())continue;
   for(String id:ids)for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByViewId(id))if(node.isVisibleToUser()&&node.isEnabled())return node;
   for(String label:labels)for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(label))if(label.equals(String.valueOf(node.getText()))&&node.isVisibleToUser()&&node.isEnabled()){
    AccessibilityNodeInfo target=node;for(int depth=0;depth<3&&!target.isClickable();depth++){AccessibilityNodeInfo parent=target.getParent();if(parent==null)break;target=parent;}
    if(target.isClickable())return target;
   }
  }return null;}finally{for(AccessibilityNodeInfo root:roots)root.recycle();}
 }
 static void press(Probe probe,String... ids)throws Exception{
  pressUnless(probe,()->false,ids);
 }
 static boolean pressUnless(Probe probe,Probe.Read resolved,String... ids)throws Exception{
  android.app.UiAutomation automation=probe.getUiAutomation();AccessibilityServiceInfo info=automation.getServiceInfo();
  info.flags|=AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS|AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;automation.setServiceInfo(info);automation.waitForIdle(350,5000);
  final AccessibilityNodeInfo[] found={null};probe.waitFor("system control "+Arrays.toString(ids),()->resolved.ready()||(found[0]=find(automation,ids))!=null);
  if(found[0]==null){probe.log("INFO permission resolved before system control activation");return false;}
  AccessibilityNodeInfo node=found[0];
  if(node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)){
   SystemClock.sleep(250);node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);long now=SystemClock.uptimeMillis();
   automation.injectInputEvent(new KeyEvent(now,now,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_CENTER,0),true);
   automation.injectInputEvent(new KeyEvent(now,now+20,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_CENTER,0),true);probe.log("PASS system control activated with D-pad");
  }else probe.check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK),"OEM system control accepts accessibility action");
  node.recycle();
  return true;
 }
 static void confirmDirectory(Probe probe)throws Exception{
  long end=SystemClock.uptimeMillis()+5000;
  while(!probe.root.hasWindowFocus()&&SystemClock.uptimeMillis()<end){boolean confirm=false;List<AccessibilityNodeInfo> roots=roots(probe.getUiAutomation());
   try{for(AccessibilityNodeInfo tree:roots)if(String.valueOf(tree.getPackageName()).contains("documentsui")&&!tree.findAccessibilityNodeInfosByText("File browser probe").isEmpty()&&!tree.findAccessibilityNodeInfosByViewId("android:id/button1").isEmpty()){confirm=true;break;}}finally{for(AccessibilityNodeInfo root:roots)root.recycle();}
   if(confirm){press(probe,"android:id/button1");return;}SystemClock.sleep(150);
  }
 }
}
