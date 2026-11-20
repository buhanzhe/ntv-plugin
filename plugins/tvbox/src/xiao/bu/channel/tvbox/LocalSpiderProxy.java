package xiao.bu.channel.tvbox;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;

final class LocalSpiderProxy implements Closeable {
    private final ServerSocket listener = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
    private final String route = "/proxy/" + UUID.randomUUID();
    private final Set<Socket> clients = Collections.synchronizedSet(new HashSet<>());
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2));
    private volatile JavaSpider spider;

    LocalSpiderProxy() throws IOException {
        new Thread(this::listen, "tvbox-proxy").start();
    }

    String url() { return "http://127.0.0.1:" + listener.getLocalPort() + route; }
    void set(JavaSpider source) { spider = source; }
    void clear(JavaSpider source) { if (spider == source) spider = null; }

    private void listen() {
        while (!listener.isClosed()) {
            try {
                Socket socket = listener.accept();
                clients.add(socket);
                try { worker.execute(() -> handle(socket)); }
                catch (RejectedExecutionException busy) { remove(socket); }
            } catch (IOException closed) { break; }
        }
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(8000);
            InputStream input = new BufferedInputStream(socket.getInputStream());
            String[] line = line(input).split(" ", 3);
            if (line.length < 2 || !line[1].startsWith("/") || !("GET".equals(line[0]) || "HEAD".equals(line[0]))) return;
            OutputStream output = socket.getOutputStream();
            HttpUrl url = HttpUrl.parse("http://127.0.0.1" + line[1]);
            JavaSpider source = spider;
            if (url == null || !route.equals(url.encodedPath()) || source == null) {
                response(output, 404, "text/plain", null, null, false);
                return;
            }
            Map<String, String> arguments = new HashMap<>();
            for (String name : url.queryParameterNames()) arguments.put(name, url.queryParameter(name));
            for (int i = 0; i < 64; i++) {
                String header = line(input);
                if (header.isEmpty()) break;
                int colon = header.indexOf(':');
                if (colon <= 0) continue;
                String key = header.substring(0, colon).toLowerCase(Locale.US);
                if ("range".equals(key) || "if-range".equals(key))
                    arguments.put(key, header.substring(colon + 1).trim());
            }
            Object[] result;
            try { result = source.proxy(arguments); }
            catch (Exception | LinkageError error) {
                response(output, 502, "text/plain", null, null, false);
                return;
            }
            if (result == null || result.length < 3 || !(result[0] instanceof Number)) {
                response(output, 404, "text/plain", null, null, false);
                return;
            }
            Object content = result[2];
            int code = ((Number) result[0]).intValue();
            InputStream stream = content instanceof InputStream ? (InputStream) content
                    : content instanceof byte[] ? new ByteArrayInputStream((byte[]) content)
                    : content instanceof String ? new ByteArrayInputStream(((String) content).getBytes("UTF-8")) : null;
            response(output, code, String.valueOf(result[1]), stream, result.length > 3 ? result[3] : null,
                    "GET".equals(line[0]));
        } catch (IOException | RuntimeException ignored) {
        } finally { remove(socket); }
    }

    private static void response(OutputStream out, int code, String mime, InputStream stream, Object headers,
                                 boolean sendBody) throws IOException {
        try (InputStream body = stream) {
            if (code < 200 || code > 599) code = 502;
            StringBuilder response = new StringBuilder("HTTP/1.1 ").append(code).append(" Response\r\nConnection: close\r\n");
            if (safe(mime)) response.append("Content-Type: ").append(mime).append("\r\n");
            if (headers instanceof Map) for (Map.Entry<?, ?> header : ((Map<?, ?>) headers).entrySet()) {
                String key = String.valueOf(header.getKey()), value = String.valueOf(header.getValue());
                if (key.matches("(?i)content-length|content-range|accept-ranges|content-encoding|location|cache-control") && safe(value))
                    response.append(key).append(": ").append(value).append("\r\n");
            }
            out.write(response.append("\r\n").toString().getBytes("UTF-8"));
            if (sendBody && body != null) {
                byte[] buffer = new byte[32768];
                for (int n; (n = body.read(buffer)) != -1;) out.write(buffer, 0, n);
            }
            out.flush();
        }
    }

    private static boolean safe(String value) { return value.indexOf('\r') < 0 && value.indexOf('\n') < 0; }

    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        for (int next; (next = in.read()) != -1 && next != '\n';) {
            if (result.size() >= 16384) throw new IOException("HTTP line too long");
            if (next != '\r') result.write(next);
        }
        return result.toString("UTF-8");
    }

    private void remove(Socket socket) {
        clients.remove(socket);
        try { socket.close(); } catch (IOException ignored) { }
    }

    public void close() {
        spider = null;
        try { listener.close(); } catch (IOException ignored) { }
        worker.shutdownNow();
        synchronized (clients) {
            for (Socket socket : clients) try { socket.close(); } catch (IOException ignored) { }
            clients.clear();
        }
    }
}
