package xiao.bu.channel.tvbox;

import java.io.*;
import java.security.MessageDigest;
import org.json.JSONObject;

/** Validated content-addressed JARs; mutable URLs are refreshed after ten minutes. */
final class SpiderJarCache {
    private static final long MAX_AGE = 10 * 60 * 1000L;
    static File find(File directory, String specification, String expectedMd5, long now) {
        try {
            File index = index(directory, specification);
            if (!index.isFile() || index.length() > 4096) return null;
            byte[] value = new byte[(int) index.length()];
            try (DataInputStream in = new DataInputStream(new FileInputStream(index))) { in.readFully(value); }
            JSONObject entry = new JSONObject(new String(value, "UTF-8"));
            String sha = entry.getString("sha256");
            long saved = entry.getLong("saved");
            if (!sha.matches("[0-9a-f]{64}") || !expectedMd5.equalsIgnoreCase(entry.optString("md5"))
                    || now < saved || expectedMd5.isEmpty() && now - saved > MAX_AGE) return null;
            File jar = new File(directory, sha + ".jar");
            if (!jar.isFile() || jar.length() == 0 || jar.length() > 32 * 1024 * 1024) return null;
            if (!sha.equals(hash(jar, "SHA-256"))) return null;
            if (!expectedMd5.isEmpty() && !expectedMd5.equalsIgnoreCase(hash(jar, "MD5"))) return null;
            return jar;
        } catch (Exception invalid) { return null; }
    }
    static void remember(File directory, String specification, String sha, String md5, long now) {
        File temp = null;
        try {
            temp = File.createTempFile("ref-", ".tmp", directory);
            JSONObject entry = new JSONObject().put("sha256", sha).put("md5", md5).put("saved", now);
            try (FileOutputStream out = new FileOutputStream(temp)) { out.write(entry.toString().getBytes("UTF-8")); }
            File index = index(directory, specification);
            if (index.exists() && !index.delete()) return;
            temp.renameTo(index);
        } catch (Exception unavailable) { /* A cache write must not prevent opening a validated JAR. */ }
        finally { if (temp != null) temp.delete(); }
    }
    private static File index(File directory, String specification) throws Exception {
        return new File(directory, "ref-" + hex(MessageDigest.getInstance("SHA-256")
                .digest(specification.getBytes("UTF-8"))) + ".json");
    }
    static String hash(File file, String algorithm) throws Exception {
        MessageDigest hash = MessageDigest.getInstance(algorithm);
        try (InputStream in = new FileInputStream(file)) {
            byte[] block = new byte[16384]; int count;
            while ((count = in.read(block)) != -1) hash.update(block, 0, count);
        }
        return hex(hash.digest());
    }
    private static String hex(byte[] data) {
        StringBuilder text = new StringBuilder();
        for (byte value : data) text.append(String.format(java.util.Locale.US, "%02x", value & 255));
        return text.toString();
    }
}
