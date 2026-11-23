package xiao.bu.channel.files;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Per-playback, loopback-only byte source. No credentials or remote paths in HTTP URLs. */
public final class MediaProxy implements Closeable {
    public interface Source { Reader open() throws IOException; }
    public interface Reader extends Closeable {
        long length() throws IOException;
        void seek(long offset) throws IOException;
        int read(byte[] buffer, int count) throws IOException;
    }
    private final ServerSocket server;
    private final Source source;
    private final String path = "/" + UUID.randomUUID().toString();
    private final ExecutorService workers = new ThreadPoolExecutor(0, 4, 15, TimeUnit.SECONDS,
            new SynchronousQueue<Runnable>(), new ThreadPoolExecutor.AbortPolicy());
    private final Set<Socket> clients = Collections.synchronizedSet(new HashSet<Socket>());
    private volatile boolean closed;

    public MediaProxy(Source source) throws IOException {
        this.source = source;
        server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        Thread accept = new Thread(() -> {
            while (!closed) {
                try {
                    Socket client = server.accept(); client.setSoTimeout(10000); clients.add(client);
                    try { workers.execute(() -> serve(client)); }
                    catch (RejectedExecutionException e) { clients.remove(client); client.close(); }
                } catch (IOException e) { if (!closed) close(); }
            }
        }, "files-proxy");
        accept.setDaemon(true); accept.start();
    }
    public String url() { return "http://127.0.0.1:" + server.getLocalPort() + path; }
    private void serve(Socket socket) {
        try {
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), "US-ASCII"));
            String request = input.readLine();
            if (request == null || request.length() > 4096) return;
            String[] parts = request.split(" ");
            OutputStream out = socket.getOutputStream();
            if (parts.length != 3 || !parts[1].equals(path)) { reply(out, "404 Not Found", "", 0); return; }
            if (!parts[0].equals("GET") && !parts[0].equals("HEAD")) { reply(out, "405 Method Not Allowed", "Allow: GET, HEAD\r\n", 0); return; }
            String range = null, line; int total = request.length();
            while ((line = input.readLine()) != null && !line.isEmpty()) {
                total += line.length(); if (total > 16384) return;
                if (line.toLowerCase(Locale.US).startsWith("range:")) {
                    if (range != null) { reply(out, "400 Bad Request", "", 0); return; }
                    range = line.substring(6).trim();
                }
            }
            boolean headersSent = false;
            try (Reader reader = source.open()) {
                long length = reader.length(), start = 0, end = length - 1;
                if (range != null) {
                    try {
                        if (!range.matches("bytes=[0-9]*-[0-9]*")) throw new IllegalArgumentException();
                        String[] pair = range.substring(6).split("-", -1);
                        if (pair[0].isEmpty()) {
                            long suffix = Long.parseLong(pair[1]);
                            if (suffix <= 0) throw new IllegalArgumentException();
                            start = Math.max(0, length - suffix);
                        } else {
                            start = Long.parseLong(pair[0]);
                            if (!pair[1].isEmpty()) end = Math.min(end, Long.parseLong(pair[1]));
                        }
                        if (start >= length || start > end) throw new IllegalArgumentException();
                    } catch (IllegalArgumentException e) { reply(out, "416 Range Not Satisfiable", "Content-Range: bytes */" + length + "\r\n", 0); return; }
                }
                long remaining = Math.max(0, end - start + 1);
                reader.seek(start);
                reply(out, range == null ? "200 OK" : "206 Partial Content",
                        "Accept-Ranges: bytes\r\nContent-Type: application/octet-stream\r\n" +
                        (range == null ? "" : "Content-Range: bytes " + start + "-" + end + "/" + length + "\r\n"), remaining);
                headersSent = true;
                if (parts[0].equals("HEAD")) return;
                byte[] buffer = new byte[65536];
                while (!closed && remaining > 0) {
                    int count = reader.read(buffer, (int)Math.min(buffer.length, remaining));
                    if (count <= 0) break;
                    out.write(buffer, 0, count); remaining -= count;
                }
            } catch (IOException e) { if (!headersSent) reply(out, "502 Bad Gateway", "", 0); }
        } catch (IOException ignored) {
        } finally { clients.remove(socket); try { socket.close(); } catch (IOException ignored) {} }
    }
    private static void reply(OutputStream out, String status, String headers, long length) throws IOException {
        out.write(("HTTP/1.1 " + status + "\r\nConnection: close\r\n" + headers + "Content-Length: " + length + "\r\n\r\n").getBytes("US-ASCII"));
    }
    public void close() {
        closed = true;
        try { server.close(); } catch (IOException ignored) {}
        synchronized (clients) { for (Socket s : clients) try { s.close(); } catch (IOException ignored) {} }
        workers.shutdownNow();
    }
}
