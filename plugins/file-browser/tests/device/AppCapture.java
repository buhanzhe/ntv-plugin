package xiao.bu.filebrowserprobe;

import android.graphics.*;
import android.os.*;
import android.view.PixelCopy;
import java.io.*;
import java.util.concurrent.*;

/** API26+ actual app-window capture, excluding separate system-status surfaces. */
final class AppCapture {
    static void save(Probe probe,String name)throws Exception{
        final Bitmap[] bitmap={null};final int[] result={-1};CountDownLatch done=new CountDownLatch(1);
        probe.ui(()->{int[] location=new int[2];probe.root.getLocationInWindow(location);
            Rect bounds=new Rect(location[0]+probe.root.getPaddingLeft(),location[1]+probe.root.getPaddingTop(),location[0]+probe.root.getWidth()-probe.root.getPaddingRight(),location[1]+probe.root.getHeight()-probe.root.getPaddingBottom());
            bitmap[0]=Bitmap.createBitmap(bounds.width(),bounds.height(),Bitmap.Config.ARGB_8888);
            PixelCopy.request(probe.activity.getWindow(),bounds,bitmap[0],status->{result[0]=status;done.countDown();},new Handler(Looper.getMainLooper()));
        });
        try{if(!done.await(5,TimeUnit.SECONDS)||result[0]!=PixelCopy.SUCCESS)throw new IOException("App capture failed: "+result[0]);
            try(OutputStream output=new FileOutputStream(new File(probe.activity.getFilesDir(),"probe-app-"+name+".png"))){bitmap[0].compress(Bitmap.CompressFormat.PNG,100,output);}
        }finally{if(bitmap[0]!=null)bitmap[0].recycle();}
        probe.log("APP_CAPTURE "+name);Thread.sleep(300);
    }
}
