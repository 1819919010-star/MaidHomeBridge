package JumDa5he.maidhomebridge.client.platform;

import JumDa5he.maidhomebridge.portal.PortalFrame;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Best-effort LAN discovery of a MaidHome Portal. Client side only and never touches the game server. */
public final class LanScanner {
    private static final int CONNECT_TIMEOUT_MS = 350;
    private static final int READ_TIMEOUT_MS = 1500;
    private static final int WORKERS = 32;
    private static final int MAX_TARGETS = 1500;

    public record Found(String host, int port) {}

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile boolean running;

    public boolean running() { return running; }
    public void cancel() { cancelled.set(true); }

    /**
     * Scans the local /24 networks plus loopback for a Portal on {@code port}. Callbacks run on the scan thread;
     * callers on the client thread must hop back via Minecraft#execute.
     */
    public void start(int port, Consumer<List<Found>> onComplete, Consumer<String> onProgress) {
        if (running) return;
        running = true;
        Thread thread = new Thread(() -> run(port, onComplete, onProgress), "MaidHome-lan-scan");
        thread.setDaemon(true);
        thread.start();
    }

    private void run(int port, Consumer<List<Found>> onComplete, Consumer<String> onProgress) {
        List<Found> found = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(WORKERS, r -> {
            Thread t = new Thread(r, "MaidHome-lan-probe");
            t.setDaemon(true);
            return t;
        });
        try {
            Set<String> targets = candidateHosts();
            if (onProgress != null) onProgress.accept("正在扫描 " + targets.size() + " 个地址，寻找 MaidHome 服务…");
            List<Future<?>> probes = new ArrayList<>();
            for (String host : targets) {
                probes.add(pool.submit(() -> {
                    if (!cancelled.get() && probe(host, port)) found.add(new Found(host, port));
                }));
            }
            for (Future<?> probe : probes) {
                try { probe.get(); } catch (Exception ignored) { }
            }
        } finally {
            pool.shutdownNow();
            running = false;
        }
        List<Found> result = new ArrayList<>(found);
        result.sort(Comparator.comparing(Found::host));
        if (onComplete != null) onComplete.accept(result);
    }

    private static Set<String> candidateHosts() {
        LinkedHashSet<String> hosts = new LinkedHashSet<>();
        hosts.add("127.0.0.1");
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements() && hosts.size() < MAX_TARGETS) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual() || ni.isPointToPoint()) continue;
                for (InterfaceAddress address : ni.getInterfaceAddresses()) {
                    InetAddress ip = address.getAddress();
                    if (!(ip instanceof Inet4Address) || ip.isLoopbackAddress() || ip.isLinkLocalAddress()) continue;
                    byte[] raw = ip.getAddress();
                    for (int last = 1; last <= 254 && hosts.size() < MAX_TARGETS; last++) {
                        hosts.add((raw[0] & 0xff) + "." + (raw[1] & 0xff) + "." + (raw[2] & 0xff) + "." + last);
                    }
                }
            }
        } catch (SocketException ignored) { }
        return hosts;
    }

    /** Connects and performs the Portal hello handshake so unrelated open ports are rejected. */
    private static boolean probe(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            JsonObject hello = new JsonObject();
            hello.addProperty("op", "hello");
            hello.addProperty("version", 1);
            hello.addProperty("client", "maidhome_bridge/scan");
            PortalFrame.write(socket.getOutputStream(), hello, new byte[0]);
            PortalFrame frame = PortalFrame.read(socket.getInputStream());
            if (!"hello.ok".equals(frame.op())) return false;
            return PortalFrame.integer(frame.header(), "version", -1, Integer.MAX_VALUE) == 1;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
