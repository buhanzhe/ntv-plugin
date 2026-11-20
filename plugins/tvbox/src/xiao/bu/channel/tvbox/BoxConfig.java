package xiao.bu.channel.tvbox;

import android.util.Base64;
import java.io.IOException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

final class BoxConfig {
    private static final Pattern ENVELOPE = Pattern.compile("[A-Za-z0-9]{8}\\*\\*");

    static JSONObject decode(byte[] data) throws Exception {
        String text = new String(data, "UTF-8").trim();
        if (text.startsWith("\ufeff")) text = text.substring(1).trim();
        if (text.startsWith("{")) return new JSONObject(text);
        // Some providers append an ASCII envelope to an image payload.
        String raw = new String(data, "ISO-8859-1").trim();
        Matcher envelope = ENVELOPE.matcher(raw);
        if (envelope.find())
            text = new String(Base64.decode(raw.substring(envelope.end()), Base64.DEFAULT), "UTF-8").trim();
        if (text.startsWith("2423")) text = decrypt(text.replaceAll("\\s+", "")).trim();
        if (!text.startsWith("{")) throw new IOException("地址未返回 TVBox 配置");
        return new JSONObject(text);
    }

    private static String decrypt(String text) throws Exception {
        String metadata = new String(hex(text), "UTF-8").toLowerCase(Locale.US);
        int start = metadata.indexOf("$#"), end = metadata.indexOf("#$", start + 2);
        int payload = text.indexOf("2324", (start + 2) * 2);
        if (start != 0 || end < 2 || end > 18 || payload < 0 || payload + 4 >= text.length() - 26)
            throw new IOException("TVBox 加密配置头无效");
        String key = metadata.substring(start + 2, end);
        byte[] password = (key + "0000000000000000").substring(0, 16).getBytes("UTF-8");
        byte[] iv = (metadata.substring(metadata.length() - 13) + "000").getBytes("UTF-8");
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(password, "AES"), new IvParameterSpec(iv));
        return new String(cipher.doFinal(hex(text.substring(payload + 4, text.length() - 26))), "UTF-8");
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
