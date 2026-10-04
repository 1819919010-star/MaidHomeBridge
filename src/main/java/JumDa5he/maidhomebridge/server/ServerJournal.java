package JumDa5he.maidhomebridge.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Disk operations are confined to ServerBridge's bounded I/O executor. */
final class ServerJournal {
    private static final Gson GSON = new Gson();
    private final Path root;
    ServerJournal(Path worldRoot) { root = worldRoot.resolve("maidhome_bridge"); }
    String scope(UUID player) throws IOException {
        Files.createDirectories(root); Path file = root.resolve("world-id.txt");
        if (!Files.exists(file)) atomic(file, UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        String worldId = Files.readString(file).trim(); UUID.fromString(worldId);
        return worldId + "/" + player;
    }
    Path receipt(UUID player, String category, String key) throws IOException {
        if (key == null || key.isBlank() || key.length() > 1024) throw new IOException("接收凭据为空或过长");
        Path directory = root.resolve("receipts").resolve(player.toString()).resolve(category);
        Files.createDirectories(directory);
        return directory.resolve(hash(key.getBytes(StandardCharsets.UTF_8)) + ".json");
    }
    JsonObject read(Path file) throws IOException {
        if (!Files.exists(file)) return null;
        if (Files.size(file) > 64 * 1024) throw new IOException("服务端记录损坏，需人工核对");
        return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    }
    void write(Path file, JsonObject value) throws IOException { atomic(file, GSON.toJson(value).getBytes(StandardCharsets.UTF_8)); }
    static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static void atomic(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".pending-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            // Fail closed on filesystems that cannot atomically commit the receipt.
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
