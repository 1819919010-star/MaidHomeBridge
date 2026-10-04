package JumDa5he.maidhomebridge.network;

import JumDa5he.maidhomebridge.server.ServerBridge;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Common-side bounded transport. This class has no client Minecraft type references. */
public final class BridgeNetwork {
    public static final int MAX_ARCHIVE = 512 * 1024;
    private static final int CHUNK = 24 * 1024, MAX_MESSAGE = 1024 * 1024, MAX_METADATA = 64 * 1024;
    private static final Gson GSON = new Gson();
    private static final Map<UUID, CompletableFuture<Message>> PENDING = new ConcurrentHashMap<>();
    private static final Map<UUID, Assembly> CLIENT_PARTS = new ConcurrentHashMap<>();
    private static final Map<UUID, Assembly> SERVER_PARTS = new ConcurrentHashMap<>();
    private BridgeNetwork() {}

    public record MaidSummary(UUID uuid, String name, String modelId) {}
    public record ExportResult(byte[] archive, UUID maidUuid, String modelId, String name,
                               UUID ownerUuid, String ownerName, String soundId,
                               String scopeId, UUID removalTicket) {}
    public record ImportResult(boolean success, String message) {}
    public record Message(String operation, JsonObject metadata, byte[] archive) {}

    public static void register(IEventBus bus) { bus.addListener(BridgeNetwork::registerPayloads); }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToServer(Request.TYPE, Request.CODEC, (packet, ctx) -> ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            try {
                Message message = collect(SERVER_PARTS, player.getUUID(), packet.id(), packet.index(), packet.total(), packet.data());
                if (message != null) ServerBridge.handle(player, packet.id(), message);
            } catch (Exception e) { fail(player, packet.id(), e.getMessage()); }
        }));
        registrar.playToClient(Response.TYPE, Response.CODEC, (packet, ctx) -> ctx.enqueueWork(() -> {
            CompletableFuture<Message> future = PENDING.get(packet.id());
            if (future == null) return;
            try {
                Message message = collect(CLIENT_PARTS, packet.id(), packet.id(), packet.index(), packet.total(), packet.data());
                if (message != null) {
                    if (message.operation().equals("error")) future.completeExceptionally(new IOException(message.metadata().get("message").getAsString()));
                    else future.complete(message);
                }
            } catch (Exception e) { future.completeExceptionally(e); }
        }));
    }

    public static CompletableFuture<List<MaidSummary>> requestMaids() {
        return request("list", new JsonObject(), new byte[0]).thenApply(m -> Arrays.asList(GSON.fromJson(m.metadata().get("maids"), MaidSummary[].class)));
    }
    public static CompletableFuture<String> requestScope() {
        return request("scope", new JsonObject(), new byte[0]).thenApply(m -> str(m.metadata(), "scopeId"));
    }
    public static CompletableFuture<Boolean> requestHousePermission() {
        return request("house_permission", new JsonObject(), new byte[0]).thenApply(m -> m.metadata().get("allowed").getAsBoolean());
    }
    public static CompletableFuture<ExportResult> requestExport(UUID maid) {
        return requestExport(maid, null);
    }
    public static CompletableFuture<ExportResult> requestExport(UUID maid, JsonObject platform) {
        JsonObject meta = new JsonObject(); meta.addProperty("maid", maid.toString());
        if(platform!=null) meta.add("platform",platform.deepCopy());
        return request("export", meta, new byte[0]).thenApply(m -> new ExportResult(m.archive(),
                uuid(m.metadata(), "maidUuid"), str(m.metadata(), "modelId"), str(m.metadata(), "name"),
                uuid(m.metadata(), "ownerUuid"), str(m.metadata(), "ownerName"), str(m.metadata(), "soundId"),
                str(m.metadata(), "scopeId"), uuid(m.metadata(), "removalTicket")));
    }
    public static CompletableFuture<ImportResult> requestImport(byte[] archive, String receiptKey) {
        return requestImport(archive, receiptKey, null);
    }
    public static CompletableFuture<ImportResult> requestImport(byte[] archive, String receiptKey, JsonObject platform) {
        if (archive.length == 0 || archive.length > MAX_ARCHIVE) return CompletableFuture.failedFuture(new IOException(".maid 文件大小超限"));
        JsonObject meta = new JsonObject(); meta.addProperty("receipt", receiptKey);
        if(platform!=null) meta.add("platform",platform.deepCopy());
        return request("import", meta, archive).thenApply(m -> new ImportResult(m.metadata().get("success").getAsBoolean(), str(m.metadata(), "message")));
    }
    /** Caller must obtain explicit user confirmation and upload.commit.ok first. */
    public static CompletableFuture<ImportResult> requestRemoval(UUID ticket, String committedUploadId) {
        return requestRemoval(ticket, committedUploadId, null);
    }
    public static CompletableFuture<ImportResult> requestRemoval(UUID ticket, String committedUploadId, JsonObject platform) {
        JsonObject meta = new JsonObject(); meta.addProperty("ticket", ticket.toString()); meta.addProperty("uploadId", committedUploadId);
        if(platform!=null) meta.add("platform",platform.deepCopy());
        return request("remove", meta, new byte[0]).thenApply(m -> new ImportResult(m.metadata().get("success").getAsBoolean(), str(m.metadata(), "message")));
    }
    public static void clearClientState() {
        PENDING.values().forEach(f -> f.completeExceptionally(new IOException("已离开世界；请重新确认传输状态")));
        PENDING.clear(); CLIENT_PARTS.clear();
    }
    public static void clearServerState() { SERVER_PARTS.clear(); ServerBridge.clear(); JumDa5he.maidhomebridge.platform.PlatformService.clear(); }

    public static CompletableFuture<JsonObject> requestPlatform(String operation, JsonObject data) {
        return request("platform_"+operation,data,new byte[0]).thenApply(Message::metadata);
    }

    private static CompletableFuture<Message> request(String operation, JsonObject meta, byte[] archive) {
        if (PENDING.size() >= 8) return CompletableFuture.failedFuture(new IOException("等待服务端的请求过多"));
        UUID id = UUID.randomUUID(); CompletableFuture<Message> future = new CompletableFuture<>();
        PENDING.put(id, future);
        future.orTimeout(60, TimeUnit.SECONDS).whenComplete((r, e) -> { PENDING.remove(id); CLIENT_PARTS.remove(id); });
        try { send(new Message(operation, meta, archive), (i, n, b) -> PacketDistributor.sendToServer(new Request(id, i, n, b))); }
        catch (Exception e) { future.completeExceptionally(e); }
        return future;
    }
    public static void reply(ServerPlayer player, UUID requestId, Message message) {
        try { send(message, (i, n, b) -> PacketDistributor.sendToPlayer(player, new Response(requestId, i, n, b))); }
        catch (Exception e) { fail(player, requestId, "服务端返回数据失败：" + e.getMessage()); }
    }
    public static void fail(ServerPlayer player, UUID id, String reason) {
        JsonObject meta = new JsonObject(); meta.addProperty("message", reason == null ? "未知服务端错误" : reason.substring(0, Math.min(2048, reason.length())));
        try { send(new Message("error", meta, new byte[0]), (i, n, b) -> PacketDistributor.sendToPlayer(player, new Response(id, i, n, b))); }
        catch (Exception ignored) { }
    }
    public static String str(JsonObject obj, String key) { return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : ""; }
    private static UUID uuid(JsonObject obj, String key) { return UUID.fromString(str(obj, key)); }
    private interface ChunkSender { void send(int index, int total, byte[] bytes); }
    private static void send(Message message, ChunkSender sender) throws IOException {
        JsonObject header = message.metadata().deepCopy(); header.addProperty("operation", message.operation());
        byte[] json = GSON.toJson(header).getBytes(StandardCharsets.UTF_8);
        if (json.length > MAX_METADATA || message.archive().length > MAX_ARCHIVE) throw new IOException("数据超过传输上限");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) { out.writeInt(json.length); out.write(json); out.writeInt(message.archive().length); out.write(message.archive()); }
        byte[] data = bytes.toByteArray(); int count = (data.length + CHUNK - 1) / CHUNK;
        for (int i = 0; i < count; i++) sender.send(i, count, Arrays.copyOfRange(data, i * CHUNK, Math.min(data.length, (i + 1) * CHUNK)));
    }
    private static synchronized Message collect(Map<UUID, Assembly> map, UUID key, UUID id, int index, int total, byte[] data) throws IOException {
        long now = System.nanoTime(); map.entrySet().removeIf(e -> now - e.getValue().created > TimeUnit.SECONDS.toNanos(60));
        if (total < 1 || total > (MAX_MESSAGE + CHUNK - 1) / CHUNK || index < 0 || index >= total || data.length > CHUNK) throw new IOException("分片边界无效");
        Assembly assembly = map.get(key);
        if (index == 0) {
            if (assembly != null) throw new IOException("已有未完成的分片请求");
            if (map.size() >= 256) throw new IOException("分片会话数超限");
            assembly = new Assembly(id, total, now); map.put(key, assembly);
        }
        if (assembly == null || !assembly.id.equals(id) || assembly.total != total || assembly.next != index) { map.remove(key); throw new IOException("分片乱序或请求不匹配"); }
        if (assembly.bytes.size() + data.length > MAX_MESSAGE) { map.remove(key); throw new IOException("消息超限"); }
        assembly.bytes.write(data); assembly.next++;
        if (assembly.next != total) return null;
        map.remove(key);
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(assembly.bytes.toByteArray()))) {
            int jsonLength = in.readInt(); if (jsonLength < 0 || jsonLength > MAX_METADATA || jsonLength > in.available()) throw new IOException("元数据超限");
            JsonObject meta = JsonParser.parseString(new String(in.readNBytes(jsonLength), StandardCharsets.UTF_8)).getAsJsonObject();
            int archiveLength = in.readInt(); if (archiveLength < 0 || archiveLength > MAX_ARCHIVE || archiveLength != in.available()) throw new IOException("归档长度错误");
            return new Message(str(meta, "operation"), meta, in.readNBytes(archiveLength));
        }
    }
    private static final class Assembly {
        final UUID id; final int total; final long created; int next; final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Assembly(UUID id, int total, long created) { this.id = id; this.total = total; this.created = created; }
    }
    public record Request(UUID id, int index, int total, byte[] data) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("maidhome_bridge", "request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.of((b, p) -> {
            b.writeUUID(p.id); b.writeVarInt(p.index); b.writeVarInt(p.total); b.writeByteArray(p.data);
        }, b -> new Request(b.readUUID(), b.readVarInt(), b.readVarInt(), b.readByteArray(CHUNK)));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
    public record Response(UUID id, int index, int total, byte[] data) implements CustomPacketPayload {
        public static final Type<Response> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("maidhome_bridge", "response"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Response> CODEC = StreamCodec.of((b, p) -> {
            b.writeUUID(p.id); b.writeVarInt(p.index); b.writeVarInt(p.total); b.writeByteArray(p.data);
        }, b -> new Response(b.readUUID(), b.readVarInt(), b.readVarInt(), b.readByteArray(CHUNK)));
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
