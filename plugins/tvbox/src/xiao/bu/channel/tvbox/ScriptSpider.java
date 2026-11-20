package xiao.bu.channel.tvbox;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import xiao.bu.tv.jar.ChannelPlugin;

final class ScriptSpider {
    static BoxSource.Spider python(ChannelPlugin.VodHost host, BoxHttp http, String url, String extend, String key)
            throws Exception {
        String session = UUID.randomUUID().toString();
        JSONObject init = new JSONObject().put("action", "init").put("session", session)
                .put("origin", url).put("siteKey", key).put("extend", extend)
                .put("source", http.text(url, 2 * 1024 * 1024));
        host.python(init);
        return new BoxSource.Spider() {
            public JSONObject invoke(String method, Object... args) throws Exception {
                return host.python(new JSONObject().put("action", method).put("session", session)
                        .put("args", new JSONArray(java.util.Arrays.asList(args))));
            }
            public void close() {
                try { host.python(new JSONObject().put("action", "close").put("session", session)); }
                catch (Exception ignored) { }
            }
        };
    }

    static final class Js implements BoxSource.Spider {
        private final ChannelPlugin.VodHost host;
        private final BoxHttp http;
        private final ClassLoader assets;
        private final HashMap<String, String> modules = new HashMap<>();
        private final HashMap<String, String> storage = new HashMap<>();
        private int cachedBytes;
        private final String url, extend, key;

        Js(ChannelPlugin.VodHost host, BoxHttp http, ClassLoader assets, String url, String extend, String key) {
            this.host = host; this.http = http; this.assets = assets;
            this.url = url; this.extend = extend; this.key = key;
        }

        public JSONObject invoke(String method, Object... args) throws Exception {
            String[] result = new String[1];
            String source = "import * as entry from " + JSONObject.quote(url) + ";\n"
                    + "const b=globalThis.NtvCjsBridge;"
                    + "const value=entry.default || entry.__ntvLegacy || entry;"
                    + "const legacy={homeContent:'home',homeVideoContent:'homeVod',categoryContent:'category',"
                    + "detailContent:'detail',searchContent:'search',playerContent:'play'};"
                    + "try {"
                    + "const spider=typeof value==='function' ? (value.prototype && value.prototype.homeContent"
                    + " ? new value() : await value()) : value;"
                    + "if(spider.init) await spider.init(" + JSONObject.quote(extend) + ");"
                    + "const name=" + JSONObject.quote(method) + ";"
                    + "const fn=spider[name] || spider[legacy[name]];"
                    + "const out=fn ? await fn.apply(spider," + new JSONArray(java.util.Arrays.asList(args)) + ") : {};"
                    + "b.complete(typeof out==='string' ? out : JSON.stringify(out));"
                    + "} catch(e) { b.fail(String(e)); }";
            host.javascript(source, new ChannelPlugin.ScriptHost() {
                public boolean isCancelled() { return Thread.currentThread().isInterrupted(); }
                public String invoke(int operation, String[] args) throws Exception {
                    switch (operation) {
                        case 2: return http.request(args[0], new JSONObject(args[1])).toString();
                        case 3: return digest(args[0], "MD5");
                        case 4: return null;
                        case 5: result[0] = args[0]; return null;
                        case 6: throw new IOException("JS 爬虫运行失败");
                        case 7: return storage.get(args[0]);
                        case 8:
                            if (storage.size() < 256 && args[1].length() < 65536) storage.put(args[0], args[1]);
                            return null;
                        case 9: return digest(args[1], args[0]);
                        case 10: return android.util.Base64.encodeToString(args[0].getBytes("UTF-8"), android.util.Base64.NO_WRAP);
                        case 11: return new String(android.util.Base64.decode(args[0], android.util.Base64.DEFAULT), "UTF-8");
                        case 15: return BoxHttp.resolve(args[0], args[1]);
                        case 16: return module(args[0]);
                        case 17: return normalize(args[0], args[1]);
                        default: throw new IOException("JS API 不受支持");
                    }
                }
            });
            if (result[0] == null) throw new IOException("JS 爬虫未返回结果");
            return new JSONObject(result[0]);
        }

        private String normalize(String base, String name) throws IOException {
            if ("catvod".equals(name) || "catvod:host".equals(name)) return name;
            if (name.equals("assets://js/lib/catvod.js")) return "catvod";
            if (name.startsWith("http://") || name.startsWith("https://")) return BoxHttp.url(name).toString();
            if (name.startsWith("./") || name.startsWith("../") || name.startsWith("/"))
                return BoxHttp.resolve(base, name);
            throw new IOException("未支持的 JS 模块");
        }

        private String module(String name) throws Exception {
            if ("catvod:host".equals(name)) return "export default {siteKey:" + JSONObject.quote(key) + "};";
            String cached = modules.get(name);
            if (cached != null) return cached;
            String code;
            if ("catvod".equals(name)) try (InputStream input = assets.getResourceAsStream("assets/catvod.js")) {
                if (input == null) throw new IOException("缺少 CatVod JS 适配器");
                code = new String(BoxHttp.read(input, 256 * 1024), "UTF-8");
            } else {
                code = http.text(name, 2 * 1024 * 1024);
                if (name.equals(url)) code += "\nexport const __ntvLegacy=typeof __jsEvalReturn === 'undefined' ? undefined : __jsEvalReturn;";
            }
            if (modules.size() >= 40 || cachedBytes + code.length() > 4 * 1024 * 1024)
                throw new IOException("JS 模块超出大小限制");
            cachedBytes += code.length();
            modules.put(name, code);
            return code;
        }

        private static String digest(String text, String algorithm) throws Exception {
            StringBuilder hash = new StringBuilder();
            for (byte b : MessageDigest.getInstance(algorithm).digest(text.getBytes("UTF-8")))
                hash.append(String.format(java.util.Locale.US, "%02x", b & 255));
            return hash.toString();
        }

        public void close() { modules.clear(); storage.clear(); }
    }
}
