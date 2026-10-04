package JumDa5he.maidhomebridge.server;

import JumDa5he.maidhomebridge.network.BridgeNetwork;
import JumDa5he.maidhomebridge.platform.PlatformService;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.zgxhzhr.maidfm.data.MaidFileData;
import io.github.zgxhzhr.maidfm.network.MaidFilePackets;
import io.github.zgxhzhr.maidfm.service.MaidTransferService;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.items.IItemHandler;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** Entity access stays on the authoritative logical server thread; archive and disk work is bounded. */
public final class ServerBridge {
    private static final Gson GSON = new Gson();
    private static final ExecutorService IO = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(128), r -> { Thread t = new Thread(r, "MaidHome-server-io"); t.setDaemon(true); return t; }, new ThreadPoolExecutor.AbortPolicy());
    private static final Map<UUID, Ticket> TICKETS = new HashMap<>();
    private static final Set<UUID> BUSY = new HashSet<>();
    private static final long TICKET_TTL = TimeUnit.MINUTES.toNanos(30);
    private ServerBridge() {}
    public static void clear() { TICKETS.clear(); BUSY.clear(); }

    public static void handle(ServerPlayer player, UUID requestId, BridgeNetwork.Message request) {
        MinecraftServer server = player.getServer();
        if (server == null) return;
        if(request.operation().startsWith("platform_")) {
            try { BridgeNetwork.reply(player,requestId,message("platform",PlatformService.handle(player,request.operation().substring(9),request.metadata()))); }
            catch(Exception e) { BridgeNetwork.fail(player,requestId,e.getMessage()); }
            return;
        }
        if (!BUSY.add(player.getUUID())) { BridgeNetwork.fail(player, requestId, "已有服务端操作正在执行，请等待完成"); return; }
        ServerJournal journal = new ServerJournal(server.getWorldPath(LevelResource.ROOT));
        CompletableFuture<BridgeNetwork.Message> result;
        try {
            result = switch (request.operation()) {
                case "scope" -> io(() -> { JsonObject m = new JsonObject(); m.addProperty("scopeId", journal.scope(player.getUUID())); return message("scope", m); });
                case "list" -> CompletableFuture.completedFuture(list(player));
                case "house_permission" -> {
                    requirePlayer(player);
                    JsonObject meta = new JsonObject();
                    // Matches MineToMesh WandRequestPolicy: integrated singleplayer or permission level two.
                    meta.addProperty("allowed", server.isSingleplayer() || player.createCommandSourceStack().hasPermission(2));
                    yield CompletableFuture.completedFuture(message("house_permission", meta));
                }
                case "export" -> export(player, request.metadata(), journal);
                case "import" -> importMaid(player, request, journal);
                case "remove" -> remove(player, request.metadata(), journal);
                default -> CompletableFuture.failedFuture(new IOException("未知桥接操作"));
            };
        } catch (Exception e) { result = CompletableFuture.failedFuture(e); }
        result.whenComplete((reply, failure) -> server.execute(() -> {
            BUSY.remove(player.getUUID());
            if (server.getPlayerList().getPlayer(player.getUUID()) != player) return;
            if (failure != null) {
                Throwable cause = failure;
                while (cause.getCause() != null) cause = cause.getCause();
                BridgeNetwork.fail(player, requestId, cause.getMessage());
            } else BridgeNetwork.reply(player, requestId, reply);
        }));
    }
    private static BridgeNetwork.Message list(ServerPlayer player) {
        var maids = MaidTransferService.listOwnMaids(player).stream().map(info -> {
            var entity = player.level().getEntity(info.entityId());
            return new BridgeNetwork.MaidSummary(entity.getUUID(), info.customName() == null ? info.displayName() : info.customName(), info.modelId());
        }).toList();
        JsonObject meta = new JsonObject(); meta.add("maids", GSON.toJsonTree(maids)); return message("list", meta);
    }
    private static CompletableFuture<BridgeNetwork.Message> export(ServerPlayer player, JsonObject request, ServerJournal journal) throws IOException {
        EntityMaid maid = ownMaid(player, UUID.fromString(BridgeNetwork.str(request, "maid")));
        PlatformService.authorizeMaid(player,request,maid);
        if (maid.isYsmModel()) throw new IOException("不支持 YSM 女仆模型，请先切换为普通模型");
        MaidFileData data = PlatformService.snapshot(player, maid);
        if (data == null) throw new IOException("MaidFileManager 拒绝导出此女仆");
        UUID ticket = UUID.randomUUID();
        TICKETS.entrySet().removeIf(e -> System.nanoTime() - e.getValue().created > TICKET_TTL
                || (e.getValue().player.equals(player.getUUID()) && e.getValue().maid.equals(maid.getUUID())));
        if (TICKETS.size() >= 128 || TICKETS.values().stream().filter(t -> t.player.equals(player.getUUID())).count() >= 16) throw new IOException("待确认导出过多，请稍后重试");
        TICKETS.put(ticket, new Ticket(player.getUUID(), maid.getUUID(), fingerprint(data), System.nanoTime(), maid.level().getGameTime()));
        JsonObject meta = new JsonObject();
        meta.addProperty("maidUuid", maid.getUUID().toString()); meta.addProperty("modelId", maid.getModelId());
        meta.addProperty("name", maid.hasCustomName() ? maid.getCustomName().getString() : data.getDisplayName());
        meta.addProperty("ownerUuid", player.getUUID().toString()); meta.addProperty("ownerName", player.getGameProfile().getName());
        meta.addProperty("soundId", maid.getSoundPackId()); meta.addProperty("removalTicket", ticket.toString());
        return io(() -> {
            byte[] archive = MaidFilePackets.serializeMaidFileData(data);
            if (archive == null || archive.length == 0 || archive.length > BridgeNetwork.MAX_ARCHIVE) throw new IOException(".maid 导出失败或大于 512 KiB");
            meta.addProperty("scopeId", journal.scope(player.getUUID()));
            return new BridgeNetwork.Message("export", meta, archive);
        });
    }
    private static CompletableFuture<BridgeNetwork.Message> importMaid(ServerPlayer player, BridgeNetwork.Message request, ServerJournal journal) {
        MinecraftServer server = player.getServer(); UUID playerId = player.getUUID();
        return io(() -> {
            byte[] archive = request.archive();
            if (archive.length == 0 || archive.length > BridgeNetwork.MAX_ARCHIVE) throw new IOException(".maid 文件大小无效");
            String digest = ServerJournal.hash(archive);
            Path receipt = journal.receipt(playerId, "imports", BridgeNetwork.str(request.metadata(), "receipt"));
            JsonObject existing = journal.read(receipt);
            if (existing != null) {
                if (!digest.equals(BridgeNetwork.str(existing, "sha256"))) throw new IOException("接收 ID 已存在但文件校验值不同");
                // A restart may have interrupted world saving. Never claim a prior spawn persisted merely from a disk receipt.
                if (!"REJECTED".equals(BridgeNetwork.str(existing, "state"))) throw new IOException("此接收记录已经处理或处于待确认状态，请核对世界中的女仆和本地接收文件，禁止盲目再次导入");
            }
            MaidFileData data = MaidFilePackets.deserializeMaidFileData(archive);
            if (data == null || data.getData() == null) throw new IOException("MaidFileManager 无法读取归档");
            String source = data.getSourceMaidUuid();
            if (source == null && data.getData().hasUUID("UUID")) source = data.getData().getUUID("UUID").toString();
            String sourceKey = source == null ? "legacy-sha256:" + digest : "maid-uuid:" + source;
            Path sourceReceipt = journal.receipt(playerId, "sources", sourceKey);
            JsonObject sourceRecord = journal.read(sourceReceipt);
            if (sourceRecord != null && !"REJECTED".equals(BridgeNetwork.str(sourceRecord, "state")))
                throw new IOException("此源女仆已有导入或待确认记录；更改 Unity ID 不能再次生成，请先人工核对");
            JsonObject record = new JsonObject(); record.addProperty("state", "PENDING"); record.addProperty("sha256", digest);
            record.addProperty("sourceMaidUuid", source); record.addProperty("createdAt", System.currentTimeMillis());
            record.addProperty("receiptKey", BridgeNetwork.str(request.metadata(), "receipt"));
            journal.write(sourceReceipt, record);
            journal.write(receipt, record);
            return new PreparedImport(data, receipt, sourceReceipt, record);
        }).thenCompose(prepared -> onServer(server, () -> {
            requirePlayer(player);
            var result = request.metadata().has("platform")
                    ? PlatformService.importAt(player,request.metadata().getAsJsonObject("platform"),prepared.data)
                    : MaidTransferService.importMaidFromData(player, prepared.data, true);
            return new Imported(prepared, result.spawned(), result.message().getString());
        })).thenCompose(imported -> io(() -> {
            imported.prepared.record.addProperty("state", imported.success ? "SPAWNED" : "REJECTED");
            imported.prepared.record.addProperty("message", imported.message);
            journal.write(imported.prepared.receipt, imported.prepared.record);
            journal.write(imported.prepared.sourceReceipt, imported.prepared.record);
            return outcome(imported.success, imported.message);
        }));
    }
    private static CompletableFuture<BridgeNetwork.Message> remove(ServerPlayer player, JsonObject request, ServerJournal journal) throws IOException {
        UUID ticketId = UUID.fromString(BridgeNetwork.str(request, "ticket"));
        String uploadId = BridgeNetwork.str(request, "uploadId");
        if (uploadId.isBlank() || uploadId.length() > 256) throw new IOException("缺少 Unity 提交回执 ID");
        Ticket ticket = TICKETS.get(ticketId);
        if (ticket == null || !ticket.player.equals(player.getUUID()) || System.nanoTime() - ticket.created > TICKET_TTL) throw new IOException("移除凭据过期或无效；请核对 Unity 中的副本后重新操作");
        PlatformService.authorizeMaid(player,request,ownMaid(player,ticket.maid));
        recheckRemoval(player, ticket);
        MinecraftServer server = player.getServer();
        return io(() -> {
            Path receipt = journal.receipt(player.getUUID(), "removals", ticketId.toString());
            if (journal.read(receipt) != null) throw new IOException("此移除已提交，需人工核对，不能盲目重试");
            JsonObject record = new JsonObject(); record.addProperty("state", "PENDING"); record.addProperty("maidUuid", ticket.maid.toString()); record.addProperty("uploadId", uploadId);
            journal.write(receipt, record); return new RemovalRecord(receipt, record);
        }).thenCompose(record -> onServer(server, () -> {
            requirePlayer(player);
            EntityMaid maid = recheckRemoval(player, ticket);
            PlatformService.authorizeMaid(player,request,maid);
            TICKETS.remove(ticketId);
            MaidTransferService.unregisterMaidWorldData(maid);
            PlatformService.removed(maid);
            maid.discard();
            return record;
        })).thenCompose(record -> io(() -> {
            record.record.addProperty("state", "REMOVED"); journal.write(record.path, record.record);
            return outcome(true, "已复核权限、背包和导出状态，移除 MC 原女仆");
        }));
    }
    private static EntityMaid recheckRemoval(ServerPlayer player, Ticket ticket) throws IOException {
        EntityMaid maid = ownMaid(player, ticket.maid);
        requireEmptyForMigration(maid);
        MaidFileData current = PlatformService.snapshot(player, maid);
        if (current == null || !SnapshotGuard.matches(ticket.snapshot, fingerprint(current), Math.max(0, maid.level().getGameTime() - ticket.gameTime))) throw new IOException("女仆状态自导出后发生变化，已保留原实体。请重新上传并确认");
        return maid;
    }
    public static void requireEmptyForMigration(EntityMaid maid) throws IOException {
        if (!empty(maid.getMaidInv()) || !empty(maid.getHideInv()) || !empty(maid.getTaskInv()) || !empty(maid.getMaidBauble())
                || !empty(maid.getHandsInvWrapper()) || !empty(maid.getArmorInvWrapper()) || !empty(maid.getAvailableBackpackInv())) {
            throw new IOException("移除已阻止：请先取回女仆背包、隐藏栏、任务栏、饰品、护甲和主副手的全部物品，再重新上传");
        }
        CompoundTag raw = maid.saveWithoutId(new CompoundTag());
        if (raw.contains("MaidBackpackData") && !raw.getCompound("MaidBackpackData").isEmpty()) throw new IOException("移除已阻止：女仆仍有背包扩展数据，请卸下背包后重新上传");
    }
    private static EntityMaid ownMaid(ServerPlayer player, UUID id) throws IOException {
        requirePlayer(player);
        var entity = player.serverLevel().getEntity(id);
        if (!(entity instanceof EntityMaid maid) || !maid.isAlive() || !maid.isOwnedBy(player) || maid.distanceToSqr(player) > 128 * 128)
            throw new IOException("女仆不在当前维度 128 格内、未加载，或你不是她的主人");
        return maid;
    }
    private static void requirePlayer(ServerPlayer player) throws IOException {
        if (player.getServer() == null || player.getServer().getPlayerList().getPlayer(player.getUUID()) != player || !player.isAlive()) throw new IOException("玩家已离线或无法操作");
    }
    private static boolean empty(IItemHandler inventory) {
        for (int i = 0; i < inventory.getSlots(); i++) if (!inventory.getStackInSlot(i).isEmpty()) return false;
        return true;
    }
    private static CompoundTag fingerprint(MaidFileData data) {
        CompoundTag root = data.writeToNbt().copy(); root.remove("exported_at");
        CompoundTag entity = root.getCompound("data");
        // MFM reconstructs Brain on import. TLM's favorability cooldown counter ticks independently of its actual favorability.
        // Progression, health, effects and addon data still must match the uploaded snapshot.
        for (String key : List.of("Pos", "Motion", "Rotation", "OnGround", "FallDistance", "Air", "Fire", "PortalCooldown", "HurtTime", "HurtByTimestamp", "DeathTime", "Brain", "FavorabilityManagerCounter")) entity.remove(key);
        return root;
    }
    private static BridgeNetwork.Message outcome(boolean success, String text) {
        JsonObject meta = new JsonObject(); meta.addProperty("success", success); meta.addProperty("message", text); return message("result", meta);
    }
    private static BridgeNetwork.Message message(String operation, JsonObject meta) { return new BridgeNetwork.Message(operation, meta, new byte[0]); }
    @FunctionalInterface private interface Checked<T> { T get() throws Exception; }
    private static <T> CompletableFuture<T> io(Checked<T> task) {
        return CompletableFuture.supplyAsync(() -> { try { return task.get(); } catch (Exception e) { throw new CompletionException(e); } }, IO);
    }
    private static <T> CompletableFuture<T> onServer(MinecraftServer server, Checked<T> task) {
        CompletableFuture<T> result = new CompletableFuture<>(); server.execute(() -> { try { result.complete(task.get()); } catch (Exception e) { result.completeExceptionally(e); } }); return result;
    }
    private record Ticket(UUID player, UUID maid, CompoundTag snapshot, long created, long gameTime) {}
    private record PreparedImport(MaidFileData data, Path receipt, Path sourceReceipt, JsonObject record) {}
    private record Imported(PreparedImport prepared, boolean success, String message) {}
    private record RemovalRecord(Path path, JsonObject record) {}
}
