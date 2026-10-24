package JumDa5he.maidhomebridge.portal;

import JumDa5he.maidhomebridge.network.BridgeNetwork;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;

public final class IncomingTransfer {
    public record Receipt(String id, String name, String hash, String aiHash, String key, Path directory,
                          byte[] archive, byte[] aiChat, JsonObject metadata, boolean processedBefore) {}
    private final TransferStore store;
    private JsonObject begin;
    private String id;
    private String error;
    private byte[] archive;
    private byte[] aiChat;
    private long total;
    private long received;
    private int fileCount;
    private int receivedFiles;
    private Receipt ready;

    public IncomingTransfer(TransferStore store) { this.store = store; }
    public synchronized boolean active() { return begin != null; }
    public synchronized Receipt ready() { return ready; }
    public synchronized String id() { return id; }
    public synchronized String error() { return error; }
    public synchronized void clear() {
        begin = null; id = null; archive = null; aiChat = null; ready = null; error = null;
        total = 0; received = 0; fileCount = 0; receivedFiles = 0;
    }

    public synchronized Receipt accept(PortalFrame frame) throws IOException {
        String op = frame.op();
        if (op.equals("send.begin")) {
            if (active()) throw new IOException("收到重叠的女仆传输");
            begin = frame.header().deepCopy();
            id = SafeFiles.id(PortalFrame.string(begin, "id"));
            if (!PortalFrame.string(begin, "kind").equals("maid")) throw new IOException("只接受 maid 返回");
            if (frame.body().length != 0) throw new IOException("send.begin 不应携带文件");
            total = PortalFrame.integer(begin, "total_bytes", -1, PortalFrame.MAX_BODY);
            fileCount = (int) PortalFrame.integer(begin, "file_count", 0, 2);
            if (total <= 0) throw new IOException("档案大小无效");
            if (fileCount != 0 && fileCount != 1 && fileCount != 2) throw new IOException("返回文件数量无效");
            return null;
        }
        if (!active() || ready != null) throw new IOException("收到顺序错误的 " + op);
        if (op.equals("send.file")) {
            try {
                String path = PortalFrame.string(frame.header(), "path");
                if (frame.body().length == 0) throw new IOException("返回文件为空");
                if (path.equals("maid_data.maid")) {
                    if (archive != null) throw new IOException("重复收到 maid_data.maid");
                    if (frame.body().length > BridgeNetwork.MAX_ARCHIVE) throw new IOException("女仆档案超过上限");
                    SafeFiles.verify(frame.body(), PortalFrame.string(frame.header(), "sha256"));
                    archive = frame.body();
                } else if (path.equals("ai_chat.json.gz")) {
                    if (aiChat != null) throw new IOException("重复收到 ai_chat.json.gz");
                    if (frame.body().length > BridgeNetwork.MAX_AI) throw new IOException("AI 聊天包超过上限");
                    SafeFiles.verify(frame.body(), PortalFrame.string(frame.header(), "sha256"));
                    aiChat = frame.body();
                } else {
                    throw new IOException("返回包含非法文件路径");
                }
                received = Math.addExact(received, frame.body().length);
                receivedFiles++;
            } catch (IOException | ArithmeticException e) { error = e.getMessage(); }
            return null;
        }
        if (!op.equals("send.end")) throw new IOException("未知接收操作");
        if (frame.body().length != 0 || !id.equals(PortalFrame.string(frame.header(), "id"))
                || PortalFrame.integer(frame.header(), "total_bytes", -1, PortalFrame.MAX_BODY) != total
                || received != total || fileCount != 0 && fileCount != receivedFiles)
            error = "send.end 身份、文件数或总长度不匹配";
        if (archive == null) error = "未收到有效 maid_data.maid";
        if (error != null) return null;
        String hash = SafeFiles.sha256(archive);
        String aiHash = aiChat == null ? "" : SafeFiles.sha256(aiChat);
        String key = TransferStore.receiptKey(id, hash, aiHash);
        JsonObject prior = store.find(key);
        boolean repeated = prior.has("imported") && prior.get("imported").getAsBoolean()
                || prior.has("state") && java.util.Set.of("IMPORT_INTENT", "IMPORT_RESULT_UNCERTAIN", "IMPORTED")
                .contains(prior.get("state").getAsString());
        Path directory = store.saveIncoming(id, archive, aiChat, begin);
        if (!prior.has("state")) {
            JsonObject record = new JsonObject();
            record.add("send_begin", begin.deepCopy()); record.addProperty("unity_id", id);
            record.addProperty("sha256", hash); record.addProperty("ai_sha256", aiHash);
            record.addProperty("directory", directory.toString());
            store.journal(key, record, "SAVED_AWAITING_CONFIRMATION");
        }
        ready = new Receipt(id, begin.has("name") ? PortalFrame.string(begin, "name") : id,
                hash, aiHash, key, directory, archive, aiChat == null ? new byte[0] : aiChat,
                begin.deepCopy(), repeated);
        return ready;
    }
}
