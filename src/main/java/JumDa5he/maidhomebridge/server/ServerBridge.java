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
                case "commit_intent", "commit_confirmed" -> commit(player,request.operation(),request.metadata(),journal);
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
        boolean transfer=request.has("transfer")&&request.get("transfer").getAsBoolean();
        if(transfer)requireEmptyForMigration(maid);
        if (maid.isYsmModel()) throw new IOException("不支持 YSM 女仆模型，请先切换为普通模型");
        MaidFileData data = PlatformService.snapshot(player, maid);
        if (data == null) throw new IOException("MaidFileManager 拒绝导出此女仆");
        UUID ticket = UUID.randomUUID();
        if(transfer) {
        TICKETS.entrySet().removeIf(e -> System.nanoTime() - e.getValue().created > TICKET_TTL
                || (e.getValue().player.equals(player.getUUID()) && e.getValue().maid.equals(maid.getUUID())));
        if (TICKETS.size() >= 128 || TICKETS.values().stream().filter(t -> t.player.equals(player.getUUID())).count() >= 16) throw new IOException("待确认导出过多，请稍后重试");
        TICKETS.put(ticket, new Ticket(player.getUUID(), maid.getUUID(), fingerprint(data), System.nanoTime(), maid.level().getGameTime()));
        }
        JsonObject meta = new JsonObject();
        meta.addProperty("maidUuid", maid.getUUID().toString()); meta.addProperty("modelId", maid.getModelId());
        meta.addProperty("name", maid.hasCustomName() ? maid.getCustomName().getString() : data.getDisplayName());
        meta.addProperty("ownerUuid", player.getUUID().toString()); meta.addProperty("ownerName", player.getGameProfile().getName());
        meta.addProperty("soundId", maid.getSoundPackId()); meta.addProperty("removalTicket", ticket.toString());
        return io(() -> {
            if(transfer&&!TransferPolicy.mayExport(journal.read(journal.receipt(player.getUUID(),"outgoing",maid.getUUID().toString()))))
                throw new IOException("这只女仆已有提交或待确认记录，请使用 confirm_remove 处理或核对两端，禁止重复上传");
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
                if (!TransferPolicy.mayReceiveTransaction(existing)) throw new IOException("此接收记录已经处理或处于待确认状态，请核对世界中的女仆和本地接收文件，禁止盲目再次导入");
            }
            MaidFileData data = MaidFilePackets.deserializeMaidFileData(archive);
            if (data == null || data.getData() == null) throw new IOException("MaidFileManager 无法读取归档");
            String source = data.getSourceMaidUuid();
            if (source == null && data.getData().hasUUID("UUID")) source = data.getData().getUUID("UUID").toString();
            String sourceKey = source == null ? "legacy-sha256:" + digest : "maid-uuid:" + source;
            Path sourceReceipt = journal.receipt(playerId, "sources", sourceKey);
            JsonObject sourceRecord = journal.read(sourceReceipt);
            if (!TransferPolicy.mayReceiveSource(sourceRecord))
                throw new IOException("此女仆仍在 MC 或上次导入结果待确认；合法移出后才能再次接收");
            JsonObject record = new JsonObject(); record.addProperty("state", "PENDING"); record.addProperty("sha256", digest);
            record.addProperty("sourceKey",sourceKey); record.addProperty("sourceMaidUuid", source); record.addProperty("createdAt", System.currentTimeMillis());
            record.addProperty("receiptKey", BridgeNetwork.str(request.metadata(), "receipt"));
            journal.write(sourceReceipt, record);
            journal.write(receipt, record);
            return new PreparedImport(data, receipt, sourceReceipt, record);
        }).thenCompose(prepared -> onServer(server, () -> {
            requirePlayer(player);
            if(!request.metadata().has("platform"))return new Imported(prepared,false,"请先启用接收台",null);
            var arrival=PlatformService.importAt(player,request.metadata().getAsJsonObject("platform"),prepared.data);
            return new Imported(prepared,arrival.result().spawned(),arrival.result().message().getString(),arrival.maid()==null?null:arrival.maid().getUUID());
        })).thenCompose(imported -> io(() -> {
            imported.prepared.record.addProperty("state", imported.success ? "SPAWNED" : "REJECTED");
            imported.prepared.record.addProperty("message", imported.message);
            if(imported.entity!=null){
                imported.prepared.record.addProperty("entityUuid",imported.entity.toString());
                journal.write(journal.receipt(playerId,"entity-origins",imported.entity.toString()),imported.prepared.record);
                JsonObject resident=new JsonObject();resident.addProperty("state","RESIDENT");resident.addProperty("arrivalReceipt",BridgeNetwork.str(imported.prepared.record,"receiptKey"));
                journal.write(journal.receipt(playerId,"outgoing",imported.entity.toString()),resident);
            }
            journal.write(imported.prepared.receipt, imported.prepared.record);
            journal.write(imported.prepared.sourceReceipt, imported.prepared.record);
            return outcome(imported.success, imported.message);
        }));
    }
    private static CompletableFuture<BridgeNetwork.Message> commit(ServerPlayer player,String operation,JsonObject request,ServerJournal journal) throws IOException {
        UUID id=UUID.fromString(BridgeNetwork.str(request,"ticket"));Ticket t=TICKETS.get(id);
        if(t==null||!t.player.equals(player.getUUID())||System.nanoTime()-t.created>TICKET_TTL)throw new IOException("发送凭据已过期");
        EntityMaid maid=ownMaid(player,t.maid);PlatformService.authorizeMaid(player,request,maid);
        if(operation.equals("commit_intent"))recheckRemoval(player,t);
        return io(()->{
            Path file=journal.receipt(player.getUUID(),"outgoing",t.maid.toString());JsonObject old=journal.read(file);
            if(operation.equals("commit_intent")) {
                if(!TransferPolicy.mayExport(old))throw new IOException("此女仆的上次提交结果待处理，禁止重复上传");
                old=new JsonObject();old.addProperty("ticket",id.toString());old.addProperty("maidUuid",t.maid.toString());old.addProperty("state","COMMIT_INTENT");
            } else {
                if(old==null||!id.toString().equals(BridgeNetwork.str(old,"ticket")))throw new IOException("提交回执与发送凭据不一致");
                String upload=BridgeNetwork.str(request,"uploadId");if(upload.isBlank()||upload.length()>256)throw new IOException("提交 ID 无效");
                if(old.has("uploadId")&&!upload.equals(BridgeNetwork.str(old,"uploadId")))throw new IOException("提交 ID 已变化");
                old.addProperty("uploadId",upload);old.addProperty("state","REMOTE_COMMITTED");
            }
            journal.write(file,old);return outcome(true,"提交状态已记录");
        });
    }
    private static CompletableFuture<BridgeNetwork.Message> remove(ServerPlayer player, JsonObject request, ServerJournal journal) throws IOException {
        UUID ticketId = UUID.fromString(BridgeNetwork.str(request, "ticket"));
        String uploadId = BridgeNetwork.str(request, "uploadId");
        if (uploadId.isBlank() || uploadId.length() > 256) throw new IOException("缺少 Unity 提交回执 ID");
        Ticket ticket = TICKETS.get(ticketId);
        if (ticket == null || !ticket.player.equals(player.getUUID()) || System.nanoTime() - ticket.created > TICKET_TTL) throw new IOException("移除凭据过期或无效；请核对两端与备份，禁止重复上传");
        PlatformService.authorizeMaid(player,request,ownMaid(player,ticket.maid));
        recheckRemoval(player, ticket);
        MinecraftServer server = player.getServer();
        return io(() -> {
            Path outgoing=journal.receipt(player.getUUID(),"outgoing",ticket.maid.toString());
            JsonObject committed=journal.read(outgoing);
            if(committed==null||!"REMOTE_COMMITTED".equals(BridgeNetwork.str(committed,"state"))||!uploadId.equals(BridgeNetwork.str(committed,"uploadId"))||!ticketId.toString().equals(BridgeNetwork.str(committed,"ticket")))throw new IOException("未确认当前任务远端提交，禁止移除");
            Path receipt = journal.receipt(player.getUUID(), "removals", ticketId.toString());
            if (journal.read(receipt) != null) throw new IOException("此移除已提交，需人工核对，不能盲目重试");
            JsonObject record = new JsonObject(); record.addProperty("state", "PENDING"); record.addProperty("maidUuid", ticket.maid.toString()); record.addProperty("uploadId", uploadId);
            journal.write(receipt, record); return new RemovalRecord(receipt, record);
        }).thenCompose(record -> onServer(server, () -> {
            requirePlayer(player);
            EntityMaid maid = recheckRemoval(player, ticket);
            PlatformService.authorizeMaid(player,request,maid);
            unregisterMaidWorldData(maid);
            TICKETS.remove(ticketId);
            PlatformService.removed(maid);
            maid.discard();
            PlatformService.playTransferSound(maid);
            return record;
        })).thenCompose(record -> io(() -> {
            record.record.addProperty("state", "REMOVED"); journal.write(record.path, record.record);
            journal.write(journal.receipt(player.getUUID(),"outgoing",ticket.maid.toString()),record.record);
            JsonObject origin=journal.read(journal.receipt(player.getUUID(),"entity-origins",ticket.maid.toString()));
            if(origin!=null){
                Path source=journal.receipt(player.getUUID(),"sources",BridgeNetwork.str(origin,"sourceKey"));JsonObject residency=journal.read(source);
                if(residency!=null&&ticket.maid.toString().equals(BridgeNetwork.str(residency,"entityUuid"))){residency.addProperty("state","OUTBOUND");residency.addProperty("outgoingUnityId",uploadId);journal.write(source,residency);}
            }
            return outcome(true, "已复核权限、背包和导出状态，移除 MC 原女仆");
        }));
    }
    private static EntityMaid recheckRemoval(ServerPlayer player, Ticket ticket) throws IOException {
        EntityMaid maid = ownMaid(player, ticket.maid);
        requireEmptyForMigration(maid);
        MaidFileData current = PlatformService.snapshot(player, maid);
        long elapsed=Math.max(0, maid.level().getGameTime()-ticket.gameTime);
        CompoundTag now=current==null?null:fingerprint(current);
        if(now==null || !SnapshotGuard.matches(ticket.snapshot,now,elapsed)) {
            String paths=now==null?"无法读取当前档案":SnapshotGuard.differences(ticket.snapshot,now,elapsed);
            org.slf4j.LoggerFactory.getLogger("MaidHomeBridge").warn("女仆 {} 传输复核失败；变化字段：{}",maid.getUUID(),paths);
            throw new IOException("女仆状态自导出后发生变化（"+paths+"），已保留原实体；若远端已提交，请核对两端后恢复，禁止重复上传");
        }
        return maid;
    }
    public static void requireEmptyForMigration(EntityMaid maid) throws IOException {
        if (!empty(maid.getMaidInv()) || !empty(maid.getHideInv()) || !empty(maid.getTaskInv()) || !empty(maid.getMaidBauble())
                || !empty(maid.getHandsInvWrapper()) || !empty(maid.getArmorInvWrapper()) || !empty(maid.getAvailableBackpackInv())) {
            throw new IOException("请先取回女仆背包、隐藏栏、任务栏、饰品、护甲和主副手的全部物品；若远端已提交，请使用 confirm_remove 复核");
        }
        CompoundTag raw = maid.saveWithoutId(new CompoundTag());
        if (raw.contains("MaidBackpackData") && !raw.getCompound("MaidBackpackData").isEmpty()) throw new IOException("移除已阻止：女仆仍有背包扩展数据，请卸下背包后重新上传");
    }
    /** TLM owns this registry; the equivalent MFM convenience method only exists in 1.4.3. */
    public static void unregisterMaidWorldData(EntityMaid maid) {
        if(maid.getOwnerUUID()==null)return;
        var worldData=com.github.tartaricacid.touhoulittlemaid.world.data.MaidWorldData.get(maid.level());
        if(worldData!=null)worldData.removeInfo(maid);
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
    private record Imported(PreparedImport prepared, boolean success, String message,UUID entity) {}
    private record RemovalRecord(Path path, JsonObject record) {}
}
