package xiao.bu.channel.tvbox;

import android.util.Base64;
import java.io.IOException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class BoxConfig {
    private static final Pattern ENVELOPE = Pattern.compile("[A-Za-z0-9]{8}\\*\\*");

    private static final Pattern PYTHON_SOURCE = Pattern.compile(
            "^([^|]+)\\|(.+?)\\|\\s*type\\s*:\\s*3\\s*\\|\\s*api\\s*:\\s*(.+)$",
            Pattern.CASE_INSENSITIVE);

    static JSONObject decode(byte[] data) throws Exception {
        String text = new String(data, "UTF-8").trim();
        if (text.startsWith("\ufeff")) text = text.substring(1).trim();
        if (text.startsWith("{")) return new JSONObject(text);
        JSONObject sites = decodePythonList(text);
        if (sites != null) return sites;
        // Some providers append an ASCII envelope to an image payload.
        String raw = new String(data, "ISO-8859-1").trim();
        Matcher envelope = ENVELOPE.matcher(raw);
        if (envelope.find())
            text = new String(Base64.decode(raw.substring(envelope.end()), Base64.DEFAULT), "UTF-8").trim();
        if (text.startsWith("2423")) text = decrypt(text.replaceAll("\\s+", "")).trim();
        if (!text.startsWith("{")) throw new IOException("地址未返回 TVBox 配置");
        return new JSONObject(text);
    }

    private static JSONObject decodePythonList(String text) throws JSONException {
        if (text.indexOf('|') < 0) return null;
        JSONArray sites = new JSONArray();
        for (String line : text.split("\\r?\\n")) {
            Matcher source = PYTHON_SOURCE.matcher(line.trim());
            if (!source.matches()) continue;
            String key = source.group(1).trim(), name = source.group(2).trim(), api = source.group(3).trim();
            if (key.isEmpty() || name.isEmpty() || !isPython(api)) continue;
            sites.put(new JSONObject().put("key", key).put("name", name).put("type", 3).put("api", api));
            if (sites.length() >= 600) break;
        }
        return sites.length() == 0 ? null : new JSONObject().put("sites", sites);
    }

    static boolean isPython(String api) {
        int query = api.indexOf('?'), fragment = api.indexOf('#');
        int end = query < 0 ? api.length() : query;
        if (fragment >= 0) end = Math.min(end, fragment);
        return api.substring(0, end).toLowerCase(Locale.US).endsWith(".py");
    }

    private static String decrypt(String text) throws Exception {
        byte[] frame = hex(text);
        int payloadEnd = frame.length - 13;
        int keyEnd = 2;
        // The key delimiter must start on a byte boundary.
        while (keyEnd + 1 < payloadEnd && (frame[keyEnd] != '#' || frame[keyEnd + 1] != '$')) keyEnd++;
        int payloadStart = keyEnd + 2;
        if (payloadStart >= payloadEnd || (payloadEnd - payloadStart) % 16 != 0)
            throw new IOException("TVBox 加密配置头无效");
        String key = new String(frame, 2, keyEnd - 2, "UTF-8").toLowerCase(Locale.US);
        if (key.length() > 16) throw new IOException("TVBox 加密配置头无效");
        byte[] password = (key + "0000000000000000").substring(0, 16).getBytes("UTF-8");
        String nonce = new String(frame, payloadEnd, 13, "UTF-8").toLowerCase(Locale.US);
        byte[] iv = (nonce + "000").getBytes("UTF-8");
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(password, "AES"), new IvParameterSpec(iv));
        return new String(cipher.doFinal(frame, payloadStart, payloadEnd - payloadStart), "UTF-8");
    }

    private static byte[] hex(String text) throws IOException {
        if ((text.length() & 1) != 0) throw new IOException("TVBox 配置编码无效");
        byte[] result = new byte[text.length() / 2];
        for (int i = 0; i < result.length; i++) {
            int high = Character.digit(text.charAt(i * 2), 16), low = Character.digit(text.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) throw new IOException("TVBox 配置编码无效");
            result[i] = (byte) (high * 16 + low);
        }
        return result;
    }
}
