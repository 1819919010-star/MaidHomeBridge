package JumDa5he.maidhomebridge.portal;

import com.google.gson.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

public final class TransferStore {
    private final Path root;
    public TransferStore(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        SafeFiles.resolve(root, "probe");
        Files.createDirectories(root);
    }
    public Path root() { return root; }
    public synchronized void journal(String key, JsonObject record, String state) throws IOException {
        record.addProperty("state", state);
        record.addProperty("updated_at", Instant.now().toString());
        writeJson(SafeFiles.resolve(root, "records/" + SafeFiles.id(key) + ".json"), record);
    }
    public synchronized JsonObject find(String key) throws IOException {
        Path file = SafeFiles.resolve(root, "records/" + SafeFiles.id(key) + ".json");
        if (!Files.exists(file)) return new JsonObject();
        try { return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject(); }
        catch (RuntimeException e) { throw new IOException("传输记录损坏，需人工核对: " + key, e); }
    }
    public Path createPackage(String kind) throws IOException {
        Path path = SafeFiles.resolve(root, "outbox/" + SafeFiles.id(kind) + "-" + UUID.randomUUID());
        Files.createDirectories(path);
        return path;
    }
    public Path saveIncoming(String id, byte[] archive, JsonObject begin) throws IOException {
        String hash = SafeFiles.sha256(archive);
        Path folder = SafeFiles.resolve(root, "inbox/" + SafeFiles.id(id) + "/" + hash);
        Path file = SafeFiles.resolve(folder, "maid_data.maid");
        if (Files.exists(file)) SafeFiles.verify(Files.readAllBytes(file), hash);
        else SafeFiles.writeDurable(file, archive);
        writeJson(SafeFiles.resolve(folder, "send.begin.json"), begin);
        return folder;
    }
    public static void writeJson(Path file, JsonObject object) throws IOException {
        SafeFiles.writeDurable(file, object.toString().getBytes(StandardCharsets.UTF_8));
    }
    public static String receiptKey(String id, String hash) {
        return "receive-" + SafeFiles.sha256((id + "\n" + hash).getBytes(StandardCharsets.UTF_8));
    }
}
