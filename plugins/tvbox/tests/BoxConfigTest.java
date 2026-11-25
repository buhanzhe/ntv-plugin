package xiao.bu.channel.tvbox;

import java.io.IOException;
import java.util.Base64;
import org.json.JSONObject;

public final class BoxConfigTest {
    // OpenSSL AES-128-CBC vectors with the public fixture IV 1234567890123000.
    // The key ab2AZ includes the delimiter's hex digits at an odd offset.
    private static final String ENCRYPTED_SITES =
            "2423616232415a2324722a5add41a0ea09db2e33ccfa101be4f7ec2d85668d5f6b410c0fb895d99a33208fbfde1585f745554a045febb60e3e59db47097cb20d5ad14ba71c23a847e506da2eae70fe28983c96172f041fc05531323334353637383930313233";
    private static final String ENCRYPTED_REPOSITORIES =
            "242373616d706c652324264ca5ad22a671aab85e0b88b1b600176670e8f5617666271b3559d45a16bd80f96a6219bf00536773ae546748ca9d2931323334353637383930313233";

    private static JSONObject decode(String text) throws Exception {
        return BoxConfig.decode(text.getBytes("UTF-8"));
    }

    private static String wrap(String text) throws Exception {
        return "binary\u0000AA11BB22**" + Base64.getMimeEncoder().encodeToString(text.getBytes("UTF-8"));
    }

    private static void reject(String text) throws Exception {
        try {
            decode(text);
            throw new AssertionError("Invalid format was accepted");
        } catch (IOException expected) {
            // The decoder should reject malformed envelopes before attempting AES.
        }
    }

    public static void main(String[] args) throws Exception {
        if (decode("\ufeff { \"sites\": [] }").getJSONArray("sites").length() != 0)
            throw new AssertionError("Plain JSON with UTF-8 BOM");
        if (!decode(wrap("{\"sites\":[{\"key\":\"cms\"}]}"))
                .getJSONArray("sites").getJSONObject(0).getString("key").equals("cms"))
            throw new AssertionError("Base64 envelope");
        String sites = ENCRYPTED_SITES.substring(0, 50) + "\n " + ENCRYPTED_SITES.substring(50);
        if (!decode(sites).getJSONArray("sites").getJSONObject(0).getString("name").equals("配置样本"))
            throw new AssertionError("Encrypted site configuration");
        if (!decode(wrap(ENCRYPTED_REPOSITORIES)).getJSONArray("urls").getJSONObject(0)
                .getString("url").equals("./child.json"))
            throw new AssertionError("Encrypted repository envelope");
        reject("<html>not a config</html>");
        reject("242");
        reject("2423zz");
        reject("242361622324");
        System.out.println("PASS plain JSON, Base64, AES sites and repositories, and malformed headers");
    }
}
