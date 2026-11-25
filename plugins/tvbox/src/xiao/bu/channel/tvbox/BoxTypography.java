package xiao.bu.channel.tvbox;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import java.io.*;
import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.*;
import okhttp3.*;

/** Original 1999 Arphic PL SungtiL: verified download, durable cache, live title refresh. */
final class BoxTypography {
    static final String SHA="705ec2dba81eaee1208e4ed5d3ff23ab259292e8e0e163ea3de297ef1317007a";
    private static final String PATH="buhanzhe/NativeWasmTv/03a02206f2b626d0e80be40fa960504068912ce1/app/src/main/assets/fonts/arphic-gbsn00lp.ttf";
    private static final String[] URLS={"https://raw.githubusercontent.com/"+PATH,
            "https://cdn.jsdelivr.net/gh/"+PATH.replace("/03a02206f2b626d0e80be40fa960504068912ce1/","@03a02206f2b626d0e80be40fa960504068912ce1/")};
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ArrayList<WeakReference<TextView>> titles=new ArrayList<>();
    private final File directory, file;
    private final OkHttpClient client;
    private volatile boolean closed;
    private volatile Call call;
    private Typeface face=Typeface.SERIF;
    private String origin="system";

    BoxTypography(Context context,OkHttpClient transport) {
        directory=new File(context.getFilesDir(),"tvbox-fonts");file=new File(directory,"sungtil-"+SHA.substring(0,16)+".ttf");
        client=transport.newBuilder().connectTimeout(6,TimeUnit.SECONDS).readTimeout(12,TimeUnit.SECONDS)
                .callTimeout(24,TimeUnit.SECONDS).followSslRedirects(false).build();
        try{face=Typeface.createFromAsset(context.getAssets(),"fonts/arphic-gbsn00lp.ttf");origin="host";}
        catch(RuntimeException unavailable){/* Older hosts acquire the exact same font below. */}
        worker.execute(() -> {
            if(file.isFile()&&verified(file)){publish(Typeface.createFromFile(file),"cache");return;}
            if(closed||"host".equals(origin))return;
            if(!directory.isDirectory()&&!directory.mkdirs())return;
            for(String address:URLS){
                if(closed)return;
                File partial=null;
                try{
                    partial=File.createTempFile("sungtil-",".part",directory);
                    call=client.newCall(new Request.Builder().url(address).build());
                    try(Response response=call.execute()){
                        if(!response.isSuccessful()||!response.request().url().isHttps()||response.body()==null)throw new IOException("Font transport");
                        if(response.body().contentLength()>6*1024*1024)throw new IOException("Font size");
                        try(InputStream in=response.body().byteStream();OutputStream out=new FileOutputStream(partial)){
                            byte[] bytes=new byte[16384];int count,total=0;
                            while((count=in.read(bytes))!=-1){if(closed||Thread.currentThread().isInterrupted())throw new IOException("Closed");total+=count;if(total>6*1024*1024)throw new IOException("Font limit");out.write(bytes,0,count);}
                        }
                    }
                    if(!verified(partial))throw new IOException("Font checksum");
                    Typeface ready=Typeface.createFromFile(partial);
                    if(file.exists()&&!file.delete())throw new IOException("Font cache");
                    if(!partial.renameTo(file))throw new IOException("Font cache");
                    publish(ready,"network");return;
                }catch(Exception unavailable){/* Keep the existing face and try the verified mirror. */}
                finally{call=null;if(partial!=null&&partial.exists())partial.delete();}
            }
        });
    }
    private static boolean verified(File file){
        if(file.length()!=5192076)return false;
        try(InputStream in=new FileInputStream(file)){
            MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] bytes=new byte[16384];int count;
            while((count=in.read(bytes))!=-1)digest.update(bytes,0,count);
            StringBuilder hex=new StringBuilder();for(byte value:digest.digest())hex.append(String.format(java.util.Locale.US,"%02x",value&255));
            return SHA.equals(hex.toString());
        }catch(Exception invalid){return false;}
    }
    private void publish(Typeface ready,String source){main.post(() -> {
        if(closed)return;face=ready;origin=source;
        Iterator<WeakReference<TextView>> iterator=titles.iterator();
        while(iterator.hasNext()){TextView title=iterator.next().get();if(title==null)iterator.remove();else apply(title);}
    });}
    private void apply(TextView title){
        boolean bold = title.getPaint().isFakeBoldText();
        title.setTypeface(face);
        title.getPaint().setFakeBoldText(bold);
        title.getPaint().setSubpixelText(true);
    }
    void title(TextView title){apply(title);titles.add(new WeakReference<>(title));}
    void close(){closed=true;Call pending=call;if(pending!=null)pending.cancel();worker.shutdownNow();main.removeCallbacksAndMessages(null);titles.clear();}
}
