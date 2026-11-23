package xiao.bu.channel.files;

import java.io.Closeable;
import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded IPv4 discovery on directly connected LANs. API-14 Java APIs only. */
public final class LanScanner implements Closeable {
    public interface Listener {
        void found(String address);
        void progress(int completed, int total);
        void finished(boolean limited);
    }
    private final Set<Socket> sockets = Collections.synchronizedSet(new HashSet<Socket>());
    private final ExecutorService workers = Executors.newFixedThreadPool(24);
    private volatile boolean cancelled;

    public void start(final Listener listener) {
        Thread coordinator = new Thread(() -> {
            LinkedHashSet<String> hosts = new LinkedHashSet<>(); boolean limited = false;
            try {
                Enumeration<NetworkInterface> networks = NetworkInterface.getNetworkInterfaces();
                while (networks != null && networks.hasMoreElements()) {
                    NetworkInterface network = networks.nextElement();
                    if (!network.isUp() || network.isLoopback()) continue;
                    for (InterfaceAddress iface : network.getInterfaceAddresses()) {
                        InetAddress address = iface.getAddress();
                        if (!(address instanceof Inet4Address) || address.isLoopbackAddress() || address.isLinkLocalAddress()) continue;
                        int prefix = iface.getNetworkPrefixLength();
                        if (prefix < 0 || prefix > 30) continue;
                        if (prefix < 24) limited = true;
                        for (String host : subnet(address.getAddress(), Math.max(24, prefix))) {
                            if (!host.equals(address.getHostAddress()) && hosts.size() < 1024) hosts.add(host);
                        }
                    }
                }
            } catch (SocketException ignored) {}
            if (!cancelled) probe(new ArrayList<>(hosts), 445, 500, listener, limited);
            else workers.shutdownNow();
        }, "files-lan-scan");
        coordinator.setDaemon(true); coordinator.start();
    }
    static List<String> subnet(byte[] address, int prefix) {
        if (address.length != 4 || prefix < 24 || prefix > 30) return Collections.emptyList();
        long ip = 0; for (byte value : address) ip = (ip << 8) | (value & 255);
        long mask = (0xffffffffL << (32 - prefix)) & 0xffffffffL, start = ip & mask, end = start | (~mask & 0xffffffffL);
        List<String> hosts = new ArrayList<>();
        for (long value = start + 1; value < end; value++) hosts.add(((value >> 24) & 255) + "." + ((value >> 16) & 255) + "." + ((value >> 8) & 255) + "." + (value & 255));
        return hosts;
    }
    // Also used by the loopback test; production always uses connected subnets and port 445.
    void probe(List<String> hosts, int port, int timeout, Listener listener, boolean limited) {
        AtomicInteger completed = new AtomicInteger(); CountDownLatch done = new CountDownLatch(hosts.size());
        try {
            for (String host : hosts) {
                if (cancelled) break;
                workers.execute(() -> {
                    try (Socket socket = new Socket()) {
                        synchronized (sockets) { if (cancelled) return; sockets.add(socket); }
                        try {
                            socket.connect(new InetSocketAddress(host, port), timeout);
                            if (!cancelled) listener.found(host);
                        } finally { sockets.remove(socket); }
                    } catch (IOException ignored) {
                    } finally { int count = completed.incrementAndGet(); if (!cancelled) listener.progress(count, hosts.size()); done.countDown(); }
                });
            }
            while (!cancelled && !done.await(200, TimeUnit.MILLISECONDS)) {}
            if (!cancelled) listener.finished(limited);
        } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        catch (RejectedExecutionException ignored) {}
        finally { workers.shutdownNow(); }
    }
    public void close() {
        cancelled = true;
        synchronized (sockets) { for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) {} }
        workers.shutdownNow();
    }
}
