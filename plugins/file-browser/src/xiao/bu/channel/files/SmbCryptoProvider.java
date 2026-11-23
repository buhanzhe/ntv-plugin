package xiao.bu.channel.files;

import java.security.MessageDigest;
import java.security.Provider;
import xiao.bu.channel.files.crypto.bc.crypto.digests.MD4Digest;

/** Explicit private NTLM digest provider; never replaces Android's process-wide BC provider. */
public final class SmbCryptoProvider extends Provider {
    public SmbCryptoProvider() {
        super("FileBrowserNTLM", 1.0, "Private SMB NTLM digest");
        put("MessageDigest.MD4", Md4.class.getName());
    }
    public static final class Md4 extends MessageDigest {
        private final MD4Digest digest = new MD4Digest();
        public Md4() { super("MD4"); }
        protected void engineUpdate(byte value) { digest.update(value); }
        protected void engineUpdate(byte[] value, int offset, int length) { digest.update(value, offset, length); }
        protected byte[] engineDigest() { byte[] result = new byte[16]; digest.doFinal(result, 0); return result; }
        protected void engineReset() { digest.reset(); }
        protected int engineGetDigestLength() { return 16; }
    }
}
