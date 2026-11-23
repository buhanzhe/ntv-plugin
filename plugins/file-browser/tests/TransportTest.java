package xiao.bu.channel.files;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises real HTTP sockets, byte ranges and (optionally) an authenticated SMB2 server. */
public final class TransportTest {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static byte[] request(String url, String method, String range) throws Exception {
        URL u = new URL(url);
        try (Socket socket = new Socket(u.getHost(), u.getPort())) {
            socket.setSoTimeout(15000);
            socket.getOutputStream().write((method + " " + u.getPath() + " HTTP/1.1\r\nHost: localhost\r\n" +
                    (range == null ? "" : "Range: " + range + "\r\n") + "\r\n").getBytes("US-ASCII"));
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[4096]; int n;
            while ((n = socket.getInputStream().read(b)) != -1) out.write(b, 0, n);
            return out.toByteArray();
        }
    }
    static byte[] body(byte[] response) throws Exception {
        String s = new String(response, "ISO-8859-1"); return Arrays.copyOfRange(response, s.indexOf("\r\n\r\n") + 4, response.length);
    }
    static void status(byte[] response, int code) throws Exception {
        check(new String(response, "ISO-8859-1").startsWith("HTTP/1.1 " + code), "HTTP " + code);
    }
    public static void main(String[] args) throws Exception {
        byte[] data = new byte[256 * 1024]; for (int i = 0; i < data.length; i++) data[i] = (byte)i;
        AtomicInteger reads = new AtomicInteger(), closed = new AtomicInteger();
        MediaProxy.Source source = () -> new MediaProxy.Reader() {
            int offset;
            public long length() { return data.length; }
            public void seek(long value) { offset = (int)value; }
            public int read(byte[] b, int count) { int n = Math.min(count, data.length - offset); System.arraycopy(data, offset, b, 0, n); offset += n; reads.addAndGet(n); return n; }
            public void close() { closed.incrementAndGet(); }
        };
        String url;
        try (MediaProxy proxy = new MediaProxy(source)) {
            url = proxy.url();
            byte[] response = request(url, "GET", "bytes=1024-2047"); status(response, 206);
            check(Arrays.equals(body(response), Arrays.copyOfRange(data, 1024, 2048)), "seek range payload");
            check(reads.get() == 1024, "stream reads requested bytes only, no full download");
            response = request(url, "HEAD", null); status(response, 200); check(body(response).length == 0, "HEAD empty");
            check(reads.get() == 1024, "HEAD never reads payload");
            response = request(url, "GET", "bytes=-128"); status(response, 206); check(body(response).length == 128, "suffix range");
            response = request(url, "GET", "bytes=262140-"); status(response, 206); check(body(response).length == 4, "open range");
            for (String invalid : new String[]{"bytes=999999-", "bytes=-0", "bytes=8-3", "bytes=0-1,3-4", "bytes=-", "bytes=99999999999999999999999-"}) status(request(url, "GET", invalid), 416);
            status(request(url + "wrong", "GET", null), 404);
            status(request(url, "POST", null), 405);
            response = request(url, "GET", null); status(response, 200); check(Arrays.equals(body(response), data), "full stream");
        }
        try { request(url, "GET", null); throw new AssertionError("port still open"); } catch (ConnectException expected) {}
        check(closed.get() == 11, "all source handles closed: " + closed.get());
        check(BrowserEntry.video("电影.MKV") && !BrowserEntry.video("movie.mp4.txt"), "video filter");
        List<String> subnet = LanScanner.subnet(new byte[]{(byte)192,(byte)168,2,12},24);
        check(subnet.size()==254 && subnet.get(0).equals("192.168.2.1") && subnet.get(253).equals("192.168.2.254"),"LAN subnet bounds");
        try (java.net.ServerSocket listener = new java.net.ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))) {
            List<String> found = new ArrayList<>();
            try (LanScanner scan = new LanScanner()) {
                scan.probe(Arrays.asList("127.0.0.1"),listener.getLocalPort(),500,new LanScanner.Listener(){
                    public void found(String address){found.add(address);} public void progress(int a,int b){} public void finished(boolean limited){}
                },false);
                check(found.equals(Arrays.asList("127.0.0.1")),"LAN discovery detects a reachable server");
            }
        }
        for (String bad : new String[]{"http://server/share/", "smb://user:secret@server/share/", "smb://server/share/?password=x"}) {
            try { new SmbSession(bad, "", "", ""); throw new AssertionError("bad SMB URL accepted"); } catch (IllegalArgumentException expected) {}
        }
        if (args.length > 0) {
            boolean guest = args.length > 1;
            try (SmbSession session = new SmbSession(args[0], "", guest ? "" : "viewer", guest ? "" : "test-password")) {
                List<BrowserEntry> files = session.list(session.root);
                BrowserEntry movie = null;
                for (BrowserEntry file : files) if (file.name.equals("电影 sample.mp4")) movie = file;
                check(movie != null, "SMB Unicode and space file listing");
                try (MediaProxy proxy = new MediaProxy(session.source(movie.location))) {
                    byte[] response = request(proxy.url(), "GET", "bytes=4096-8191"); status(response, 206);
                    check(Arrays.equals(body(response), Arrays.copyOfRange(data, 4096, 8192)), "authenticated SMB2 seek payload");
                }
            }
            System.out.println("PASS " + (guest ? "password-free" : "authenticated") + " SMB2 list / Unicode / seek / stream / close");
        }
        System.out.println("PASS HTTP range / HEAD / invalid ranges / token / method / bounded reads / cleanup");
    }
}
