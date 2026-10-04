package JumDa5he.maidhomebridge.portal;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;

public final class IncomingTransfer {
    public record Receipt(String id, String name, String hash, String key, Path directory,
                          byte[] archive, JsonObject metadata, boolean processedBefore) {}
    private final TransferStore store;
    private JsonObject begin;
    private String id;
    private String error;
    private byte[] archive;
    private long total;
    private Receipt ready;

    public IncomingTransfer(TransferStore store) { this.store = store; }
    public synchronized boolean active() { return begin != null; }
    public synchronized Receipt ready() { return ready; }
    public synchronized String id() { return id; }
    public synchronized String error() { return error; }
    public synchronized void clear() { begin = null; id = null; archive = null; ready = null; error = null; }

    public synchronized Receipt accept(PortalFrame frame) throws IOException {
        String op = frame.op();
        if (op.equals("send.begin")) {
            if (active()) throw new IOException("收到重叠的女仆传输");
            begin = frame.header().deepCopy();
            id = SafeFiles.id(PortalFrame.string(begin, "id"));
            if (!PortalFrame.string(begin, "kind").equals("maid")) throw new IOException("只接受 maid 返回");
            if (frame.body().length != 0) throw new IOException("send.begin 不应携带文件");
            total = PortalFrame.integer(begin, "total_bytes", -1, PortalFrame.MAX_BODY);
            if (total <= 0) throw new IOException("档案大小无效");
            return null;
        }
        if (!active() || ready != null) throw new IOException("收到顺序错误的 " + op);
        if (op.equals("send.file")) {
            try {
                if (archive != null) throw new IOException("返回协议只允许一个档案文件");
                if (!PortalFrame.string(frame.header(), "path").equals("maid_data.maid")) throw new IOException("返回包含非法文件路径");
                if (frame.body().length != total) throw new IOException("档案长度与 send.begin 不符");
                SafeFiles.verify(frame.body(), PortalFrame.string(frame.header(), "sha256"));
                archive = frame.body();
            } catch (IOException e) { error = e.getMessage(); }
            return null;
        }
        if (!op.equals("send.end")) throw new IOException("未知接收操作");
        if (frame.body().length != 0 || !id.equals(PortalFrame.string(frame.header(), "id"))
                || PortalFrame.integer(frame.header(), "total_bytes", -1, PortalFrame.MAX_BODY) != total)
            error = "send.end 身份或长度不匹配";
        if (archive == null) error = "未收到有效 maid_data.maid";
        if (error != null) return null;
        String hash = SafeFiles.sha256(archive);
        String key = TransferStore.receiptKey(id, hash);
        JsonObject prior = store.find(key);
        boolean repeated = prior.has("imported") && prior.get("imported").getAsBoolean()
                || prior.has("state") && java.util.Set.of("IMPORT_INTENT", "IMPORT_RESULT_UNCERTAIN", "IMPORTED")
                .contains(prior.get("state").getAsString());
        Path directory = store.saveIncoming(id, archive, begin);
        if (!prior.has("state")) {
            JsonObject record = new JsonObject();
            record.add("send_begin", begin.deepCopy()); record.addProperty("unity_id", id);
            record.addProperty("sha256", hash); record.addProperty("directory", directory.toString());
            store.journal(key, record, "SAVED_AWAITING_CONFIRMATION");
        }
        ready = new Receipt(id, begin.has("name") ? PortalFrame.string(begin, "name") : id,
                hash, key, directory, archive, begin.deepCopy(), repeated);
        return ready;
    }
}
