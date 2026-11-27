package xiao.bu.channel.tvbox;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

final class BoxHeif {
    private static boolean attempted;
    private static boolean loaded;

    static Bitmap decode(Context context, ClassLoader assets, byte[] bytes, int edge) {
        if (Build.VERSION.SDK_INT < 15 || !isHeic(bytes) || !load(context, assets)) return null;
        return decodeNative(bytes, edge);
    }

    private static boolean isHeic(byte[] bytes) {
        if (bytes.length < 16 || bytes[4] != 'f' || bytes[5] != 't' || bytes[6] != 'y' || bytes[7] != 'p'
                || bytes[0] != 0 || bytes[1] != 0 || bytes[2] != 0) return false;
        int end = Math.min(bytes[3] & 0xff, bytes.length);
        for (int offset = 8; offset + 4 <= end; offset += 4) {
            if (offset == 12 || bytes[offset] != 'h' || bytes[offset + 1] != 'e') continue;
            int third = bytes[offset + 2], fourth = bytes[offset + 3];
            if ((third == 'i' && (fourth == 'c' || fourth == 'x'))
                    || (third == 'v' && (fourth == 'c' || fourth == 'x'))) return true;
        }
        return false;
    }

    @SuppressWarnings("deprecation") // CPU_ABI identifies this process, including 32-bit processes on a 64-bit device.
    private static synchronized boolean load(Context context, ClassLoader assets) {
        if (attempted) return loaded;
        attempted = true;
        String abi = Build.CPU_ABI;
        if (!"armeabi-v7a".equals(abi) && !"arm64-v8a".equals(abi) && !"x86".equals(abi)) return false;
        if (Build.VERSION.SDK_INT < 19) {
            if (!"armeabi-v7a".equals(abi)) return false;
            abi = "armeabi-v7a-ics";
        }
        String resource = "assets/heif/" + abi + "/libntv_box_heif.so";
        File file = null;
        try (InputStream input = assets.getResourceAsStream(resource)) {
            if (input == null) return false;
            file = File.createTempFile("ntv-heif-", ".so", context.getCacheDir());
            try (FileOutputStream output = new FileOutputStream(file)) {
                byte[] buffer = new byte[16384];
                for (int count; (count = input.read(buffer)) != -1;) output.write(buffer, 0, count);
            }
            System.load(file.getAbsolutePath());
            loaded = true;
        } catch (IOException | LinkageError | SecurityException unavailable) {
            return false;
        } finally {
            if (file != null) file.delete();
        }
        return true;
    }

    private static native Bitmap decodeNative(byte[] bytes, int edge);
}
