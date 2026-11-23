package xiao.bu.channel.files;

import java.io.*;
import java.net.URI;
import java.util.*;
import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.*;

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
        CIFSContext base = new BaseContext(new PropertyConfiguration(config));
        context = user.isEmpty() ? base.withGuestCrendentials()
                : base.withCredentials(new NtlmPasswordAuthenticator(domain, user, password));
    }
    public List<BrowserEntry> list(String url) throws IOException {
        List<BrowserEntry> result = new ArrayList<>();
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
