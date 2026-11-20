package xiao.bu.python;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONObject;
import xiao.bu.tv.jar.PythonPlugin;

public final class RuntimePlugin implements PythonPlugin {
    public void initialize(Context context, File archive) throws Exception {
        File directory = new File(context.getFilesDir(), "python-runtime/" + archive.getName());
        String root = directory.getCanonicalPath();
        long expanded = 0;
        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith("runtime/")) continue;
                File target = new File(directory, entry.getName().substring("runtime/".length()));
                if (!target.getCanonicalPath().startsWith(root + File.separator))
                    throw new IOException("Python 插件条目路径无效");
                File parent = target.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("无法准备 Python 文件");
                File temp = File.createTempFile("python-", ".tmp", parent);
                try {
                    try (InputStream input = zip.getInputStream(entry); FileOutputStream out = new FileOutputStream(temp)) {
                        byte[] buffer = new byte[16384];
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            expanded += count;
                            if (expanded > 96L * 1024 * 1024) throw new IOException("Python 插件解压内容过大");
                            out.write(buffer, 0, count);
                        }
                    }
                    if (!temp.setReadOnly() || !temp.renameTo(target)) throw new IOException("无法保存 Python 文件");
                } finally {
                    temp.delete();
                }
            }
        }
        File lib = new File(directory, "lib");
        String[] libraries = new File(lib, "libpython3.11.so").isFile()
                ? new String[]{"python3.11", "ntvpython"}
                : new String[]{"crypto_python", "ssl_python", "sqlite3_python", "python3.14", "ntvpython"};
        for (String name : libraries) System.load(new File(lib, "lib" + name + ".so").getAbsolutePath());
        start(root);
    }

    public JSONObject invoke(JSONObject request) throws Exception {
        byte[] response = run(request.toString().getBytes("UTF-8"));
        if (response == null || response.length > 8 * 1024 * 1024) throw new IOException("Python 响应无效或过大");
        JSONObject envelope = new JSONObject(new String(response, "UTF-8"));
        if (envelope.has("error")) throw new IOException(envelope.getString("error"));
        return envelope.getJSONObject("result");
    }

    private static native void start(String directory) throws IOException;
    private static native byte[] run(byte[] input) throws IOException;
}
