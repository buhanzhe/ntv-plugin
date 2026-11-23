package xiao.bu.channel.tvbox;

import android.content.Context;
import android.util.LruCache;
import com.github.catvod.Proxy;
import com.github.catvod.crawler.Spider;
import dalvik.system.DexClassLoader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;

final class JavaSpider implements BoxSource.Spider, LocalSpiderProxy.Source {
    static final class Jars {
        private final Context context;
        private final BoxHttp http;
        private LocalSpiderProxy proxy;
        private final LruCache<String, DexClassLoader> loaders = new LruCache<>(6);

        Jars(Context context, BoxHttp http) { this.context = context; this.http = http; }

        JavaSpider open(BoxSource.Site site, String specification, String extend) throws Exception {
            if (proxy == null) proxy = new LocalSpiderProxy();
            Proxy.configure(proxy.url());
            DexClassLoader loader = loaders.get(specification);
            if (loader == null) {
                File file = fetch(specification);
                loader = new DexClassLoader(file.getPath(), file.getParent(), null, JavaSpider.class.getClassLoader());
                initializeJar(loader, context);
                loaders.put(specification, loader);
            }
            String name = site.api.substring(4);
            if (!name.matches("[A-Za-z_$][A-Za-z0-9_$]*")) throw new IOException("CatVod 入口名无效");
            Class<?> type;
            try { type = loader.loadClass("com.github.catvod.spider." + name); }
            catch (ClassNotFoundException absent) { type = loader.loadClass("com.github.catvod.crawler." + name); }
            Object target = type.getConstructor().newInstance();
            if (target instanceof Spider) {
                ((Spider) target).attach(context);
                ((Spider) target).siteKey = site.key;
            }
            JavaSpider spider = new JavaSpider(target, loader, proxy);
            try {
                Method init = optional(type, "init", Context.class, String.class);
                if (init != null) call(init, target, context, extend);
                else call(type.getMethod("init", Context.class), target, context);
                proxy.set(spider);
                return spider;
            } catch (Exception | LinkageError ex) {
                spider.close();
                throw ex;
            }
        }

        private File fetch(String specification) throws Exception {
            String[] parts = specification.split(";md5;", 2);
            String md5 = parts.length == 2 ? parts[1] : "";
            if (md5.startsWith("http")) md5 = http.text(md5, 1024).trim();
            if (!md5.isEmpty() && !md5.matches("(?i)[0-9a-f]{32}")) throw new IOException("CatVod JAR 的 MD5 无效");
            File dir = new File(context.getCacheDir(), "tvbox-spiders");
            if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) throw new IOException("无法创建 CatVod 缓存");
            File temp = File.createTempFile("jar-", ".tmp", dir);
            try {
                MessageDigest hash = MessageDigest.getInstance("SHA-256");
                MessageDigest legacyHash = MessageDigest.getInstance("MD5");
                try (Response response = http.get(parts[0])) {
                    if (!response.isSuccessful() || response.body() == null) throw new IOException("CatVod JAR HTTP " + response.code());
                    try (InputStream in = response.body().byteStream(); FileOutputStream out = new FileOutputStream(temp)) {
                        byte[] buffer = new byte[16384];
                        int size = 0;
                        for (int n; (n = in.read(buffer)) != -1;) {
                            size += n;
                            if (size > 32 * 1024 * 1024) throw new IOException("CatVod JAR 超过 32 MB");
                            hash.update(buffer, 0, n);
                            legacyHash.update(buffer, 0, n);
                            out.write(buffer, 0, n);
                        }
                    }
                }
                if (!md5.isEmpty() && !md5.equalsIgnoreCase(hex(legacyHash.digest())))
                    throw new IOException("CatVod JAR 的 MD5 不匹配");
                File target = new File(dir, hex(hash.digest()) + ".jar");
                if (!target.isFile() && (!temp.setReadOnly() || !temp.renameTo(target)))
                    throw new IOException("无法保存 CatVod JAR");
                return target;
            } finally {
                temp.delete();
            }
        }

        private static void initializeJar(ClassLoader loader, Context context) throws Exception {
            Class<?> init;
            try { init = loader.loadClass("com.github.catvod.spider.Init"); }
            catch (ClassNotFoundException absent) { return; }
            call(init.getMethod("init", Context.class), null, context);
        }

        void close() {
            if (proxy != null) proxy.close();
            loaders.evictAll();
        }
    }

    private final Object target;
    private final Class<?> type;
    private final ClassLoader loader;
    private final LocalSpiderProxy server;

    private JavaSpider(Object target, ClassLoader loader, LocalSpiderProxy server) {
        this.target = target;
        this.type = target.getClass();
        this.loader = loader;
        this.server = server;
    }

    public JSONObject invoke(String method, Object... args) throws Exception {
        Object result;
        switch (method) {
            case "homeContent":
                result = call(type.getMethod(method, boolean.class), target, args); break;
            case "homeVideoContent":
                result = call(type.getMethod(method), target); break;
            case "detailContent":
                result = call(type.getMethod(method, List.class), target, strings((JSONArray) args[0])); break;
            case "categoryContent":
                JSONObject filters = (JSONObject) args[3];
                HashMap<String, String> extend = new HashMap<>();
                for (Iterator<String> keys = filters.keys(); keys.hasNext();) {
                    String key = keys.next();
                    extend.put(key, filters.optString(key));
                }
                result = call(type.getMethod(method, String.class, String.class, boolean.class, HashMap.class),
                        target, args[0], args[1], args[2], extend); break;
            case "searchContent":
                Method paged = optional(type, method, String.class, boolean.class, String.class);
                result = paged == null ? call(type.getMethod(method, String.class, boolean.class), target, args[0], args[1])
                        : call(paged, target, args); break;
            case "playerContent":
                result = call(type.getMethod(method, String.class, String.class, List.class),
                        target, args[0], args[1], strings((JSONArray) args[2])); break;
            default: throw new IllegalArgumentException("未知爬虫操作");
        }
        return new JSONObject(String.valueOf(result));
    }

    @Override public Object[] proxy(Map<String, String> query) throws Exception {
        Method method = optional(type, "localProxy", Map.class);
        Object[] response = method == null ? null : (Object[]) call(method, target, query);
        if (response != null) return response;
        method = optional(type, "proxy", Map.class);
        if (method != null && !(target instanceof Spider)) {
            response = (Object[]) call(method, target, query);
            if (response != null) return response;
        }
        Class<?> gateway;
        try { gateway = loader.loadClass("com.github.catvod.spider.Proxy"); }
        catch (ClassNotFoundException absent) { return null; }
        return (Object[]) call(gateway.getMethod("proxy", Map.class), null, query);
    }

    public void close() {
        server.clear(this);
        for (String method : new String[]{"close", "destroy"}) {
            try {
                Method cleanup = optional(type, method);
                if (cleanup != null) call(cleanup, target);
            } catch (Exception | LinkageError ignored) { }
        }
    }

    private static Method optional(Class<?> type, String name, Class<?>... args) {
        try { return type.getMethod(name, args); }
        catch (NoSuchMethodException missing) { return null; }
    }

    private static Object call(Method method, Object target, Object... args) throws Exception {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof LinkageError) throw (LinkageError) cause;
            if (cause instanceof Exception) throw (Exception) cause;
            throw error;
        }
    }

    private static List<String> strings(JSONArray array) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) result.add(array.optString(i));
        return result;
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) result.append(String.format(java.util.Locale.US, "%02x", b & 255));
        return result.toString();
    }
}
