package xiao.bu.channel.files;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.*;
import java.util.*;

/** Isolated from the API-15 entry point; call only after the relevant SDK check. */
final class ModernStorage {
    static boolean needsLocalNetwork(Context context){return android.os.Build.VERSION.SDK_INT>=37&&context.getApplicationInfo().targetSdkVersion>=37;}
    static boolean localNetworkGranted(Context context){return context.checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK")==android.content.pm.PackageManager.PERMISSION_GRANTED;}
    static boolean declaresLocalNetwork(Context context){
        try{String[] names=context.getPackageManager().getPackageInfo(context.getPackageName(),android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions;
            if(names!=null)for(String name:names)if("android.permission.ACCESS_LOCAL_NETWORK".equals(name))return true;
        }catch(android.content.pm.PackageManager.NameNotFoundException ignored){}return false;
    }
    static void requestLocalNetwork(android.app.Activity activity){activity.requestPermissions(new String[]{"android.permission.ACCESS_LOCAL_NETWORK"},0x4643);}
    static String readPermission(Context context) {
        String permission=android.os.Build.VERSION.SDK_INT>=33?"android.permission.READ_MEDIA_VIDEO":android.Manifest.permission.READ_EXTERNAL_STORAGE;
        try {String[] declared=context.getPackageManager().getPackageInfo(context.getPackageName(),android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions;
            if(declared!=null)for(String name:declared)if(permission.equals(name))return permission;
        }catch(android.content.pm.PackageManager.NameNotFoundException ignored){}
        return null;
    }
    static boolean canReadSharedStorage(Context context) { // API 23
        String permission=readPermission(context);
        if(permission==null||context.checkSelfPermission(permission)!=android.content.pm.PackageManager.PERMISSION_GRANTED)return false;
        android.app.AppOpsManager ops=(android.app.AppOpsManager)context.getSystemService(Context.APP_OPS_SERVICE);String op=android.app.AppOpsManager.permissionToOp(permission);
        if(ops==null||op==null)return true;int mode=ops.checkOpNoThrow(op,android.os.Process.myUid(),context.getPackageName());
        return mode==android.app.AppOpsManager.MODE_ALLOWED||mode==android.app.AppOpsManager.MODE_DEFAULT;
    }
    static boolean runtimeReadGranted(Context context){String permission=readPermission(context);return permission!=null&&context.checkSelfPermission(permission)==android.content.pm.PackageManager.PERMISSION_GRANTED;}
    static void requestSharedStorage(android.app.Activity activity) { // API 23
        String permission=readPermission(activity);if(permission!=null)activity.requestPermissions(new String[]{permission}, 0x4642);
    }
    static String saveTree(Context context,Uri tree,int tab) throws IOException { // API 21
        String id=DocumentsContract.getTreeDocumentId(tree);
        Uri document=DocumentsContract.buildDocumentUriUsingTree(tree,id);String name="已授权目录";
        try(Cursor cursor=context.getContentResolver().query(document,new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){
            if(cursor==null||!cursor.moveToFirst())throw new IOException("授权目录不可读");name=cursor.getString(0);
        }
        boolean durable=false;
        try{context.getContentResolver().takePersistableUriPermission(tree,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);durable=true;}
        catch(SecurityException ignored){} // Some providers only grant this session.
        if(durable){
            android.content.SharedPreferences prefs=context.getSharedPreferences("file-browser",0);
            try{org.json.JSONArray saved=new org.json.JSONArray(prefs.getString("trees","[]"));org.json.JSONArray next=new org.json.JSONArray();
                for(int i=0;i<saved.length();i++){org.json.JSONObject item=saved.getJSONObject(i);if(!tree.toString().equals(item.optString("uri"))&&next.length()<23)next.put(item);}
                next.put(new org.json.JSONObject().put("uri",tree.toString()).put("name",name).put("tab",tab));prefs.edit().putString("trees",next.toString()).apply();
            }catch(org.json.JSONException error){throw new IOException("无法记录目录授权",error);}
        }
        return name;
    }
    static List<BrowserEntry> savedTrees(Context context,int tab) { // API 21
        List<BrowserEntry> result=new ArrayList<>();Set<String> granted=new HashSet<>();
        for(android.content.UriPermission permission:context.getContentResolver().getPersistedUriPermissions())if(permission.isReadPermission())granted.add(permission.getUri().toString());
        try{org.json.JSONArray saved=new org.json.JSONArray(context.getSharedPreferences("file-browser",0).getString("trees","[]"));
            for(int i=0;i<saved.length();i++){org.json.JSONObject item=saved.getJSONObject(i);String uri=item.optString("uri");
                if(granted.contains(uri)&&(tab<0||item.optInt("tab",0)==tab))result.add(new BrowserEntry(item.optString("name","已授权目录"),uri,true,1));
            }
        }catch(org.json.JSONException ignored){}
        return result;
    }
    static File[] externalDirs(Context context) { return context.getExternalFilesDirs(null); } // API 19
    static List<BrowserEntry> documents(Context context, Uri tree) throws IOException { // API 21
        List<BrowserEntry> result = new ArrayList<>();
        String id = DocumentsContract.isDocumentUri(context, tree) ? DocumentsContract.getDocumentId(tree) : DocumentsContract.getTreeDocumentId(tree);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
        try (Cursor cursor = context.getContentResolver().query(children, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (cursor == null) throw new IOException("目录不可读");
            while (cursor.moveToNext()) {
                String name = cursor.getString(1), mime = cursor.getString(2);
                boolean dir = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                if (dir || (mime != null && mime.startsWith("video/")) || BrowserEntry.video(name))
                    result.add(new BrowserEntry(name, DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)).toString(), dir, 1));
            }
        }
        return result;
    }
}
