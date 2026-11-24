package xiao.bu.channel.files;

import java.io.*;
import java.net.URI;
import java.util.*;
import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.*;
import jcifs.dcerpc.DcerpcHandle;
import jcifs.dcerpc.msrpc.MsrpcShareEnum;

/** Explicit server/share connection; credentials never form part of a URL. */
public final class SmbSession implements Closeable {
    static { jcifs.util.Crypto.initProvider(new SmbCryptoProvider()); }
    private final CIFSContext context;
    public final String root;
    public SmbSession(String address, String domain, String user, String password) throws Exception {
        address = address.replace(" ", "%20");
        URI uri = new URI(address.endsWith("/") ? address : address + "/");
        if (!"smb".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null || uri.getPort() > 65535
                || uri.getPort() == 0 || uri.getPath().contains("/../"))
            throw new IllegalArgumentException("请输入 smb://服务器/共享名/，账号密码请填在独立输入框");
        // jcifs expects decoded share/path names, including Chinese and spaces.
        root = "smb://" + uri.getRawAuthority() + uri.getPath();
        Properties config = new Properties();
        config.setProperty("jcifs.smb.client.minVersion", "SMB202");
        config.setProperty("jcifs.smb.client.maxVersion", "SMB311");
        config.setProperty("jcifs.smb.client.connTimeout", "8000");
        config.setProperty("jcifs.smb.client.soTimeout", "10000");
        config.setProperty("jcifs.smb.client.responseTimeout", "10000");
        config.setProperty("jcifs.smb.client.disableSMB1", "true");
        // Guest sessions have no signing key. Permit their share-enumeration
        // pipe without imposing the library's extra client-side IPC policy.
        // Authenticated sessions and server-required signing remain enforced.
        if(user.isEmpty())config.setProperty("jcifs.smb.client.ipcSigningEnforced","false");
        // ICS omits Cp850. SMB2 paths/credentials use Unicode; OEM is only a legacy fallback.
        config.setProperty("jcifs.encoding", "US-ASCII");
        config.setProperty("jcifs.smb.client.useUnicode", "true");
        config.setProperty("jcifs.smb.client.forceUnicode", "true");
        CIFSContext base = new BaseContext(new PropertyConfiguration(config));
        context = user.isEmpty() ? base.withGuestCrendentials()
                : base.withCredentials(new NtlmPasswordAuthenticator(domain, user, password));
    }
    public List<BrowserEntry> list(String url) throws IOException {
        List<BrowserEntry> result = new ArrayList<>();
        // The library's server-root RPC enumeration drops the URL port. Preserve
        // it explicitly so forwarded/custom-port servers behave like port 445.
        final URI uri;
        try {uri=new URI(url.replace(" ","%20"));}catch(java.net.URISyntaxException error){throw new IOException("无效的 SMB 路径",error);}
        if(uri.getPort()>0&&(uri.getPath()==null||"/".equals(uri.getPath()))){
            try(DcerpcHandle handle=DcerpcHandle.getHandle("ncacn_np:"+uri.getRawAuthority()+"[\\PIPE\\srvsvc]",context)){
                MsrpcShareEnum call=new MsrpcShareEnum(uri.getHost());handle.sendrecv(call);
                if(call.retval!=0)throw new SmbException(call.retval,true);
                for(FileEntry entry:call.getEntries())if(entry.getType()==jcifs.SmbConstants.TYPE_SHARE){
                    String name=entry.getName();result.add(new BrowserEntry(name+"/","smb://"+uri.getRawAuthority()+"/"+name+"/",true,2));
                }
            }
            return result;
        }
        try (SmbFile folder = new SmbFile(url, context)) {
            SmbFile[] files = folder.listFiles();
            try {
                for (SmbFile file : files) {
                    boolean directory = file.isDirectory(); String name = file.getName();
                    if (directory || BrowserEntry.video(name)) result.add(new BrowserEntry(name, file.getCanonicalPath(), directory, 2));
                }
            } finally { for (SmbFile file : files) file.close(); }
        }
        return result;
    }
    public MediaProxy.Source source(final String url) {
        return () -> {
            final SmbFile file = new SmbFile(url, context);
            final SmbRandomAccessFile input;
            try { input = file.openRandomAccess("r"); }
            catch (IOException e) { file.close(); throw e; }
            return new MediaProxy.Reader() {
                public long length() throws IOException { return input.length(); }
                public void seek(long offset) throws IOException { input.seek(offset); }
                public int read(byte[] buffer, int count) throws IOException { return input.read(buffer, 0, count); }
                public void close() throws IOException { try { input.close(); } finally { file.close(); } }
            };
        };
    }
    public void close() { try { context.close(); } catch (Exception ignored) {} }
}
