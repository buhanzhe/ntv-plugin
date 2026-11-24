package xiao.bu.channel.files;

import java.security.MessageDigest;
import java.security.Provider;
import xiao.bu.channel.files.crypto.bc.crypto.digests.MD4Digest;
import xiao.bu.channel.files.crypto.bc.crypto.engines.AESEngine;
import xiao.bu.channel.files.crypto.bc.crypto.macs.CMac;
import xiao.bu.channel.files.crypto.bc.crypto.params.KeyParameter;

/** Explicit private NTLM digest provider; never replaces Android's process-wide BC provider. */
public final class SmbCryptoProvider extends Provider {
    public SmbCryptoProvider() {
        super("FileBrowserNTLM", 1.0, "Private SMB NTLM digest");
        put("MessageDigest.MD4", Md4.class.getName());
        put("Mac.AESCMAC", AesCmac.class.getName());
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
    /** SMB 3 signing needs AES-CMAC. Keep the provider private to this plugin. */
    public static final class AesCmac extends javax.crypto.MacSpi {
        private final CMac mac=new CMac(new AESEngine());
        public AesCmac() {}
        protected int engineGetMacLength(){return 16;}
        protected void engineInit(java.security.Key key,java.security.spec.AlgorithmParameterSpec params)
                throws java.security.InvalidKeyException,java.security.InvalidAlgorithmParameterException {
            if(params!=null)throw new java.security.InvalidAlgorithmParameterException("AES-CMAC has no parameters");
            byte[] bytes=key==null?null:key.getEncoded();
            if(bytes==null||(bytes.length!=16&&bytes.length!=24&&bytes.length!=32))throw new java.security.InvalidKeyException("Invalid AES key length");
            mac.init(new KeyParameter(bytes));
        }
        protected void engineUpdate(byte value){mac.update(value);}
        protected void engineUpdate(byte[] value,int offset,int count){mac.update(value,offset,count);}
        protected byte[] engineDoFinal(){byte[] out=new byte[16];mac.doFinal(out,0);return out;}
        protected void engineReset(){mac.reset();}
    }
}
