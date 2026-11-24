package xiao.bu.filebrowserprobe;
import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import android.webkit.ValueCallback;
public final class ProbeActivity extends Activity {
 private ValueCallback<Uri[]> selected;
 public void onCreate(Bundle state){super.onCreate(state);getWindow().addFlags(128);}
 public void chooseTree(ValueCallback<Uri[]> callback){selected=callback;Intent intent=new Intent("android.intent.action.OPEN_DOCUMENT_TREE").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
  String id=android.os.Build.VERSION.SDK_INT>=30?"primary:Movies/"+getIntent().getStringExtra("fixture"):"primary:Android/data/"+getPackageName()+"/files";
  intent.putExtra("android.provider.extra.INITIAL_URI",Uri.parse("content://com.android.externalstorage.documents/document/"+Uri.encode(id)));startActivityForResult(intent,701);
 }
 protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==701&&selected!=null){ValueCallback<Uri[]> callback=selected;selected=null;callback.onReceiveValue(result==RESULT_OK&&data!=null&&data.getData()!=null?new Uri[]{data.getData()}:null);}}
}
