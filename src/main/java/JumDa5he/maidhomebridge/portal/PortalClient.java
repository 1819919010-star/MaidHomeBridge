package JumDa5he.maidhomebridge.portal;

import com.google.gson.JsonObject;
import java.io.*;
import java.net.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class PortalClient implements AutoCloseable {
    public interface Notifications {
        void frame(PortalFrame frame) throws IOException;
        void disconnected(String reason);
    }
    private final Socket socket = new Socket();
    private final Object writeLock = new Object();
    private final Object requestLock = new Object();
    private final Notifications notifications;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "MaidHome-timeouts"); t.setDaemon(true); return t;
    });
    private final long timeoutMs;
    private volatile CompletableFuture<JsonObject> pending;
    private volatile String pendingOp;
    private volatile boolean closed;
    private InputStream input;
    private OutputStream output;

    public PortalClient(String host, int port, Duration timeout, Notifications notifications) throws IOException {
        this.notifications = notifications;
        this.timeoutMs = timeout.toMillis();
        if (timeoutMs < 100 || timeoutMs > 300_000) throw new IOException("超时设置越界");
        try {
            socket.connect(new InetSocketAddress(host, port), (int) timeoutMs);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout((int) Math.max(60_000, timeoutMs));
            input = new BufferedInputStream(socket.getInputStream());
            output = new BufferedOutputStream(socket.getOutputStream());
            Thread reader = new Thread(this::readLoop, "MaidHome-reader");
            reader.setDaemon(true);
            reader.start();
        } catch (IOException e) { close(); throw e; }
    }

    public boolean connected() { return !closed; }

    public JsonObject hello() throws IOException {
        JsonObject hello = message("hello");
        hello.addProperty("version", 1);
        hello.addProperty("client", "maidhome_bridge/0.2.1 (JumDa5he)");
        JsonObject response = request(hello, new byte[0]);
        if (PortalFrame.integer(response, "version", -1, Integer.MAX_VALUE) != 1) {
            close(); throw new IOException("Unity 协议版本不是 1");
        }
        return response;
    }

    public void startHeartbeat(Consumer<String> error) {
        timer.scheduleWithFixedDelay(() -> {
            if (closed || pending != null) return;
            Thread.startVirtualThread(() -> {
                try { request(message("ping"), new byte[0]); }
                catch (IOException e) { error.accept(e.getMessage()); }
            });
        }, 15, 15, TimeUnit.SECONDS);
    }

    public JsonObject request(JsonObject header, byte[] body) throws IOException {
        synchronized (requestLock) {
            if (closed) throw new IOException("Portal 未连接");
            String op = PortalFrame.string(header, "op");
            if (op.equals("send.ack")) throw new IOException("send.ack 没有应答，不能作为请求等待");
            CompletableFuture<JsonObject> reply = new CompletableFuture<>();
            pendingOp = op;
            pending = reply;
            try {
                send(header, body);
                return reply.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                fail("等待 " + op + " 超时；操作结果可能待确认");
                throw new IOException("等待 " + op + " 超时；请核对两端状态", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); close(); throw new IOException("请求被中断", e);
            } catch (ExecutionException e) { throw new IOException(e.getCause().getMessage(), e.getCause()); }
            finally { pending = null; pendingOp = null; }
        }
    }

    public void acknowledge(String id, boolean ok, String reason) throws IOException {
        JsonObject ack = message("send.ack");
        ack.addProperty("id", id); ack.addProperty("ok", ok); ack.addProperty("message", reason);
        send(ack, new byte[0]);
    }

    private void send(JsonObject header, byte[] body) throws IOException {
        synchronized (writeLock) {
            if (closed) throw new IOException("Portal 已断开");
            ScheduledFuture<?> watchdog = timer.schedule(() -> fail("写入超时；结果待确认"), timeoutMs, TimeUnit.MILLISECONDS);
            try { PortalFrame.write(output, header, body); }
            catch (IOException e) { fail(e.getMessage()); throw e; }
            finally { watchdog.cancel(false); }
        }
    }

    private void readLoop() {
        try {
            while (!closed) {
                PortalFrame frame = PortalFrame.read(input);
                String op = frame.op();
                if (op.equals("send.begin") || op.equals("send.file") || op.equals("send.end")) {
                    notifications.frame(frame);
                    continue;
                }
                CompletableFuture<JsonObject> target = pending;
                String request = pendingOp;
                if (target == null || request == null || target.isDone()) throw new IOException("收到意外应答: " + op);
                if (frame.body().length != 0) throw new IOException("应答不应包含 body");
                if (op.equals("error")) {
                    if (!request.equals(PortalFrame.string(frame.header(), "request"))) throw new IOException("错误应答与请求不匹配");
                    target.completeExceptionally(new IOException(PortalFrame.string(frame.header(), "message")));
                } else {
                    String expected = request.equals("ping") ? "pong" : request.equals("list.request") ? "list.ok" : request + ".ok";
                    if (!op.equals(expected)) throw new IOException("应答错位: 期待 " + expected + "，收到 " + op);
                    target.complete(frame.header());
                }
            }
        } catch (Exception e) { fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()); }
    }

    private synchronized void fail(String reason) {
        if (closed) return;
        closed = true;
        try { socket.close(); } catch (IOException ignored) {}
        CompletableFuture<JsonObject> target = pending;
        if (target != null) target.completeExceptionally(new IOException(reason));
        timer.shutdownNow();
        notifications.disconnected(reason);
    }

    public static JsonObject message(String op) { JsonObject o = new JsonObject(); o.addProperty("op", op); return o; }
    @Override public void close() { fail("连接已关闭"); }
}
