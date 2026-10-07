package JumDa5he.maidhomebridge.client;

import JumDa5he.maidhomebridge.network.BridgeNetwork;
import JumDa5he.maidhomebridge.client.resource.MaidResourceExporter;
import JumDa5he.maidhomebridge.client.resource.SoundPackExporter;
import JumDa5he.maidhomebridge.client.house.HouseExporter;
import JumDa5he.maidhomebridge.portal.*;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonObject;
import io.github.zgxhzhr.maidfm.network.MaidFilePackets;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;

public final class BridgeClientService {
    private static final org.slf4j.Logger LOGGER=org.slf4j.LoggerFactory.getLogger("MaidHomeBridge");
    public static final BridgeClientService INSTANCE = new BridgeClientService();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MaidHome-client-worker"); t.setDaemon(true); return t;
    });
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicLong generation = new AtomicLong();
    private final ThreadLocal<Long> jobGeneration = new ThreadLocal<>();
    private volatile PortalClient portal;
    private volatile IncomingTransfer incoming;
    private volatile TransferStore store;
    private volatile PackageUploader uploading;
    private volatile String scope;
    private volatile String status = "尚未连接";
    private volatile UUID selected;
    private volatile long lastProgress;
    private volatile PendingRemoval removal;
    private volatile boolean cancelRequested;
    private volatile boolean rejectIncoming;
    private final AtomicReference<String> nextReceiveMode=new AtomicReference<>("import");
    private final ThreadLocal<JsonObject> jobPlatform = new ThreadLocal<>();
    private volatile JsonObject activePlatform;
    private volatile boolean platformBroken, remoteRisk, unsafeResult, ackWriteStarted;
    private volatile String endpointHost = "127.0.0.1";
    private volatile int endpointPort = 7411;
    private volatile boolean endpointLoaded;
    private volatile String waitMode = "";
    private volatile long nextPulse;
    private volatile CompletableFuture<JsonObject> pulse;
    private final ConcurrentLinkedDeque<String> history = new ConcurrentLinkedDeque<>();
    private final ConcurrentHashMap<String, com.google.gson.JsonArray> remoteLists = new ConcurrentHashMap<>();
    private volatile java.util.List<String> diskRecords = java.util.List.of();
    private volatile java.util.List<BridgeNetwork.MaidSummary> ownMaids = java.util.List.of();
    private record PendingRemoval(UUID ticket, String unityId, String recordKey, String name, String scope, UUID maid, JsonObject station) {}
    private BridgeClientService() {}

    public void select(UUID id) { selected = id; say("已选择女仆 " + id + "；上传时由服务端验证所有权。"); }
    public void maids() {
        run("读取女仆列表", () -> {
            var maids = onClientAsync(BridgeNetwork::requestMaids);
            ownMaids = java.util.List.copyOf(maids);
            if (maids.isEmpty()) say("没有找到可操作的女仆。");
            for (var maid : maids) {
                clickable(maid.name() + " [" + maid.modelId() + "] " + maid.uuid(), "/maidhome select " + maid.uuid());
            }
        });
    }

    public void connect(String host, int port) {
        run("连接 Unity", () -> {
            if (portal != null && portal.connected()) throw new IOException("已经连接，请先断开或使用界面重连");
            configureEndpoint(host,port);
            remoteLists.clear();
            String context = onClientAsync(BridgeNetwork::requestScope);
            long token = jobGeneration.get();
            Path game = onClient(() -> Minecraft.getInstance().gameDirectory.toPath());
            TransferStore storage = new TransferStore(game.resolve("maidhome_bridge").resolve("sessions")
                    .resolve(SafeFiles.sha256(context.getBytes(StandardCharsets.UTF_8))));
            IncomingTransfer receiving = new IncomingTransfer(storage);
            AtomicReference<PortalClient> connection = new AtomicReference<>();
            AtomicReference<CompletableFuture<JsonObject>> binding=new AtomicReference<>();
            PortalClient client = new PortalClient(host, port, Duration.ofSeconds(30), new PortalClient.Notifications() {
                @Override public void frame(PortalFrame frame) throws IOException {
                    if (generation.get() != token) throw new IOException("当前世界已切换");
                    if(frame.op().equals("send.begin")) {
                        binding.set(busy.get()?CompletableFuture.failedFuture(new IOException("另一项传输正在进行")):beginReceiver(token));
                        say("正在接收女仆");
                    }
                    IncomingTransfer.Receipt receipt;
                    try {receipt=receiving.accept(frame);}
                    catch(IOException e) {
                        finishReceiver(binding.get(),"error",e.getMessage());
                        if(frame.op().equals("send.end")&&connection.get()!=null&&receiving.id()!=null){connection.get().acknowledge(receiving.id(),false,"档案保存或校验失败");receiving.clear();return;}
                        throw e;
                    }
                    if(frame.op().equals("send.end")&&receiving.error()!=null){
                        connection.get().acknowledge(receiving.id(),false,receiving.error());
                        finishReceiver(binding.get(),"error",receiving.error());say("接收失败："+receiving.error());receiving.clear();return;
                    }
                    if(receipt!=null) {
                        if(rejectIncoming||busy.get()) {
                            connection.get().acknowledge(receipt.id(),false,"当前无法接收，请稍后再试");receiving.clear();
                            finishReceiver(binding.get(),"cancelled","接收已取消");rejectIncoming=false;return;
                        }
                        processArrival(connection.get(),receiving,receipt,binding.get(),token,nextReceiveMode.getAndSet("import"));
                    }
                }
                @Override public void disconnected(String reason) {
                    if (generation.get() != token) return;
                    status = "已断开：" + reason;
                    say(status + "。备份已保留；提交或确认期间断线请核对两端。");
                }
            });
            connection.set(client);
            if (generation.get() != token) { client.close(); throw new IOException("已离开世界"); }
            portal = client; incoming = receiving; store = storage; scope = context; rejectIncoming = false;
            if (generation.get() != token) { client.close(); throw new IOException("连接任务已取消"); }
            try { client.hello(); }
            catch (IOException e) { client.close(); throw e; }
            if (generation.get() != token) { client.close(); throw new IOException("连接任务已取消"); }
            client.startHeartbeat(this::say);
            status = "已连接 " + host + ":" + port;
            LOGGER.info("备份目录：{}",storage.root()); say(status);
        });
    }

    public void disconnect() {
        if(net.neoforged.fml.ModList.get().isLoaded("minetomesh")) HouseExporter.cancel();
        JsonObject station=activePlatform;
        if(station!=null && Minecraft.getInstance().player!=null) {
            JsonObject end=station.deepCopy();end.addProperty("outcome",remoteRisk?"uncertain":"cancelled");end.addProperty("detail","连接已断开，请核对两端与备份记录");
            BridgeNetwork.requestPlatform("finish",end);
        }
        generation.incrementAndGet(); cancelRequested = true;
        PackageUploader upload = uploading; if (upload != null) upload.cancel();
        PortalClient client = portal; portal = null; if (client != null) client.close();
        incoming = null; activePlatform=null; nextReceiveMode.set("import"); status = "已断开，备份保留";
        say(status);
    }
    public void logout() { disconnect(); removal=null; selected = null; ownMaids=java.util.List.of(); remoteLists.clear(); diskRecords=java.util.List.of(); BridgeNetwork.clearClientState(); }
    public void resourceReloaded() {
        cancelRequested = true;
        PackageUploader upload = uploading;
        if (upload != null) upload.cancel();
    }
    public void cancel() {
        cancelRequested = true;
        PackageUploader upload = uploading;
        if (upload != null) { upload.cancel(); say("已请求取消，将在当前文件应答后终止上传。提交已发出时需核对结果。"); }
        else if (incoming != null && incoming.ready() != null) { if (busy.get()) rejectIncoming = true; else receive("reject"); }
        else if (incoming != null && incoming.active()) { rejectIncoming = true; say("接收结束后将拒绝确认，Unity 原档案保留。"); }
        else say("已请求取消当前准备任务。");
    }
    public void status() {
        say(status + (busy.get() ? "；任务进行中" : ""));
        if (store != null) say("本地备份／传输记录：" + store.root());
        if (selected != null) say("已选女仆：" + selected);
    }
    public void list(String kind) {
        run("查询 Unity 列表", () -> {
            PortalClient client = requirePortal();
            com.google.gson.JsonArray items = queryList(kind);
            say("Unity " + kind + " 列表：");
            int shown = 0;
            for (var item : items) {
                if (++shown > 100) { say("仅显示前 100 项，请在 Unity 面板查看其余内容。"); break; }
                say(item.toString());
            }
            if (shown == 0) say("（空）");
        });
    }

    public void receive(String mode) {
        if(!java.util.Set.of("save","import","reject").contains(mode)){say("接收模式无效");return;}
        if(jobGeneration.get()==null&&(incoming==null||incoming.ready()==null)) {
            nextReceiveMode.set(mode);say(switch(mode){case "save"->"下次回传仅保存备份，MaidHome 女仆会保留";case "reject"->"下次回传将拒绝接收";default->"下次回传将导入已启用的接收台";});return;
        }
        run("确认返回档案", () -> {
            PortalClient client = requirePortal(); IncomingTransfer receiving = incoming;
            IncomingTransfer.Receipt receipt = receiving == null ? null : receiving.ready();
            if (receipt == null) throw new IOException("没有待确认的返回档案");
            TransferStore storage = store;
            JsonObject record = storage.find(receipt.key());
            record.addProperty("unity_id", receipt.id()); record.addProperty("sha256", receipt.hash());
            record.addProperty("directory", receipt.directory().toString());
            if (mode.equals("reject") || mode.equals("save")) {
                storage.journal(receipt.key(), record, "REJECTED");
                client.acknowledge(receipt.id(), false, mode.equals("save")?"仅保存备份，未生成实体":"玩家拒绝接收"); receiving.clear();
                say("本地备份保留，MaidHome 女仆未移除。"); return;
            }
            validateArchive(receipt.archive());
            if (mode.equals("import")) {
                boolean priorImport = record.has("imported") && record.get("imported").getAsBoolean()
                        || record.has("state") && java.util.Set.of("IMPORT_INTENT", "IMPORT_RESULT_UNCERTAIN", "IMPORTED", "ACK_RESULT_UNCERTAIN", "ACK_SENT_NO_REPLY")
                        .contains(record.get("state").getAsString());
                if (receipt.processedBefore() || priorImport) throw new IOException("同一接收事务已有处理记录，请核对两端，已阻止重复生成");
                if(activePlatform==null)throw new IOException("请先启用接收台");
                storage.journal(receipt.key(), record, "IMPORT_INTENT");
                unsafeResult = true; remoteRisk = true;
                BridgeNetwork.ImportResult result;
                try { result = onClientAsync(() -> BridgeNetwork.requestImport(receipt.archive(), receipt.key(), activePlatform)); }
                catch (Exception e) {
                    storage.journal(receipt.key(), record, "IMPORT_RESULT_UNCERTAIN");
                    client.acknowledge(receipt.id(), false, "导入结果待确认，保留 Unity 档案"); receiving.clear();
                    throw e;
                }
                unsafeResult = false;
                if (!result.success()) {
                    storage.journal(receipt.key(), record, "IMPORT_REJECTED");
                    client.acknowledge(receipt.id(), false, result.message()); receiving.clear();
                    throw new IOException("导入被前置拒绝：" + result.message() + "。Unity 女仆保留。");
                }
                unsafeResult=true;
                record.addProperty("imported", true);
                storage.journal(receipt.key(), record, "IMPORTED");
            } else if (!mode.equals("save")) throw new IOException("接收模式无效");
            SafeFiles.verify(java.nio.file.Files.readAllBytes(receipt.directory().resolve("maid_data.maid")), receipt.hash());
            unsafeResult = true; remoteRisk = true;
            storage.journal(receipt.key(), record, "ACK_RESULT_UNCERTAIN");
            ackWriteStarted=true;
            client.acknowledge(receipt.id(), true, "MaidFileManager 导入成功");
            receiving.clear();
            storage.journal(receipt.key(), record, "ACK_SENT_NO_REPLY");
            unsafeResult = false;
            LOGGER.info("返回备份：{}；ACK 无应答，需在 MaidHome 核对删除结果",receipt.directory());
            say("已导入台面；请在 MaidHome 确认原存档已移除");
        });
    }

    public void confirmRemoval() {
        run("复核并移除 MC 原女仆", () -> {
            PendingRemoval pending = removal;
            if (pending == null || !pending.scope.equals(scope)) throw new IOException("没有可确认的已提交迁移");
            JsonObject record = store.find(pending.recordKey);
            unsafeResult = true; remoteRisk = true;
            store.journal(pending.recordKey, record, "REMOVAL_RESULT_UNCERTAIN");
            onClientAsync(() -> BridgeNetwork.requestCommit("commit_confirmed",pending.ticket,pending.unityId,activePlatform));
            BridgeNetwork.ImportResult result = onClientAsync(() -> BridgeNetwork.requestRemoval(pending.ticket, pending.unityId, activePlatform));
            unsafeResult = false;
            store.journal(pending.recordKey, record, result.success() ? "COMMITTED_AND_REMOVED" : "COMMITTED_SOURCE_RETAINED");
            if (!result.success()) throw new IOException("远端已提交 / 本地移除失败："+result.message());
            removal=null;
            if(activePlatform==null&&pending.station!=null) {
                try {onClientAsync(()->BridgeNetwork.requestPlatform("acknowledge",pending.station));}
                catch(Exception e){LOGGER.warn("移除成功，传输台警告未清除",e);}
            }
            status="女仆已传送，MC 原实体已移除";say(status);
        });
    }

    public void exportMaid(boolean migrate) {
        run("发送女仆", () -> {
            unsafeResult=false;
            if(!connected())connect(endpointHost(),endpointPort());
            requireTransferIdle();
            UUID maidId = jobPlatform.get() == null ? selected : UUID.fromString(jobPlatform.get().get("maid").getAsString());
            if(removal!=null&&removal.maid.equals(maidId))throw new IOException("远端已提交 / 本地移除待处理，请使用 confirm_remove，不要再次上传");
            if (maidId == null) throw new IOException("请先 /maidhome maids 选择女仆");
            BridgeNetwork.ExportResult data = onClientAsync(() -> BridgeNetwork.requestMaidExport(maidId, activePlatform));
            if (!data.scopeId().equals(scope)) throw new IOException("世界／玩家身份已变化，请重新连接 Unity");
            var snapshot = onClient(() -> {
                var level = Minecraft.getInstance().level;
                if (level == null) throw new IllegalStateException("没有当前世界");
                EntityMaid maid = null;
                for (var entity : level.entitiesForRendering())
                    if (entity.getUUID().equals(maidId) && entity instanceof EntityMaid found) { maid = found; break; }
                if (maid == null) throw new IllegalStateException("所选女仆未在客户端加载，请靠近后重试");
                if (!maid.getModelId().equals(data.modelId())) throw new IllegalStateException("模型刚发生变化，请重新上传");
                return MaidResourceExporter.capture(maid, data);
            });
            snapshot.warnings().forEach(LOGGER::warn);
            say("女仆档案与模型准备完成");
            checkCancelled();
            Path folder = store.createPackage("maid");
            MaidResourceExporter.export(snapshot, data.archive(), folder, message -> {
                LOGGER.warn("{}", message);
                say("警告：" + message);
            });
            onClient(() -> { MaidResourceExporter.validate(snapshot); return null; });
            checkCancelled(); requireTransferIdle();
            {
                say("检查女仆音效包");
                var sounds = queryList("sound");
                boolean exists = false;
                for (var item : sounds) if (item.isJsonObject() && data.soundId().equals(BridgeNetwork.str(item.getAsJsonObject(), "id"))) exists = true;
                if (exists) say("音效包已存在，跳过上传（未比较内容版本）");
                else { progress("先发送所需音效包：" + data.soundId()); uploadSoundPackage(data.soundId()); }
                checkCancelled();
            }
            JsonObject identity = new JsonObject(); identity.addProperty("source_maid_uuid", data.maidUuid().toString());
            identity.addProperty("archive_sha256", SafeFiles.sha256(data.archive())); identity.addProperty("scope", scope);
            identity.addProperty("migration_requested", true);
            uploading = uploader().beforeCommit(() -> {
                checkCancelled();
                try { validatePlatform();onClientAsync(() -> BridgeNetwork.requestCommit("commit_intent",data.removalTicket(),"",activePlatform)); unsafeResult=true; }
                catch(Exception e){throw new CompletionException(e);}
            });
            PackageUploader.Result result = uploading.upload("maid", null, data.name(), folder, identity, this::progress);
            removal = new PendingRemoval(data.removalTicket(),result.id(),result.recordKey(),data.name(),scope,data.maidUuid(),jobPlatform.get()==null?null:jobPlatform.get().deepCopy());
            unsafeResult=true;
            try {confirmRemoval();}
            catch(Exception e){unsafeResult=true;status="远端已提交 / 本地移除失败，请核对两端，禁止重复上传";say(status);throw e;}
        });
    }

    public void uploadSound() {
        run("上传女仆音效包", () -> {
            requireTransferIdle();
            UUID maidId = selected;
            if (maidId == null) throw new IOException("请先选择女仆，音效包将使用她的实际音效 ID");
            BridgeNetwork.ExportResult data = onClientAsync(() -> BridgeNetwork.requestExport(maidId));
            if (!data.scopeId().equals(scope)) throw new IOException("世界／玩家身份已变化");
            uploadSoundPackage(data.soundId());
        });
    }

    private void uploadSoundPackage(String soundId) throws Exception {
            var snapshot = onClient(() -> SoundPackExporter.capture(soundId));
            Path folder = store.createPackage("sound");
            SoundPackExporter.export(snapshot, folder, this::progress);
            onClient(() -> { SoundPackExporter.validate(snapshot); return null; });
            checkCancelled(); requireTransferIdle();
            uploading = uploader();
            PackageUploader.Result result = uploading.upload("sound", soundId, soundId, folder, new JsonObject(), this::progress);
            LOGGER.info("音效已提交：{}",result.id());status = "音效已提交"; say(status);
    }

    public void uploadHouse(String name) {
        run("导出并上传 MineToMesh 房屋", () -> {
            requireTransferIdle();
            if (!onClientAsync(BridgeNetwork::requestHousePermission)) throw new IOException("服务器不允许你导出房屋选区（需要相应权限）");
            Path folder = store.createPackage("house");
            CompletableFuture<Path> export = onClient(() -> HouseExporter.export(name, folder.resolve("payload"), this::progress));
            Path exported;
            try { exported = export.get(15, TimeUnit.MINUTES); }
            catch (TimeoutException e) { onClient(() -> { HouseExporter.cancel(); return null; }); throw new IOException("房屋导出超过 15 分钟，已取消", e); }
            checkCancelled(); requireTransferIdle();
            uploading = uploader();
            PackageUploader.Result result = uploading.upload("house", null, name, exported, new JsonObject(), this::progress);
            LOGGER.info("房屋已提交：{}",result.id());status = "房屋已提交"; say(status);
        });
    }

    private PortalClient requirePortal() throws IOException {
        PortalClient client = portal;
        if (client == null || !client.connected()) throw new IOException("先执行 /maidhome connect");
        return client;
    }
    private void requireTransferIdle() throws IOException {
        requirePortal();
        if (incoming != null && incoming.active()) throw new IOException("Unity 正在发送女仆，请先确认或拒绝接收");
    }
    private void checkCancelled() {
        if (platformBroken && jobPlatform.get() != null) throw new CancellationException("传输台或目标已失效");
        if (cancelRequested || jobGeneration.get() != null && jobGeneration.get() != generation.get())
            throw new CancellationException("任务已取消或世界已切换");
    }
    private static void validateArchive(byte[] archive) throws IOException {
        if (archive.length == 0 || archive.length > BridgeNetwork.MAX_ARCHIVE) throw new IOException("档案超过 MaidFileManager 的 512 KiB 上限");
        var data = MaidFilePackets.deserializeMaidFileData(archive);
        if (data == null || data.getData() == null) throw new IOException("不是有效的 MaidFileManager 女仆档案");
    }
    private void progress(String text) {
        LOGGER.debug("{}",text);
        var uploadProgress=java.util.regex.Pattern.compile("上传 (maid|sound|house) (\\d+)/(\\d+) 字节.*").matcher(text);
        if(uploadProgress.matches()) {
            String kind=switch(uploadProgress.group(1)){case "maid"->"女仆";case "sound"->"音效";default->"房屋";};
            long total=Long.parseLong(uploadProgress.group(3)),sent=Long.parseLong(uploadProgress.group(2));
            text="正在发送"+kind+"："+(total==0?100:sent*100/total)+"%";
        }
        status = text;
        long now = System.nanoTime();
        if (now - lastProgress >= TimeUnit.SECONDS.toNanos(1)) { lastProgress = now; say(text); }
    }
    @FunctionalInterface private interface Operation { void run() throws Exception; }
    private void run(String description, Operation operation) {
        if (jobGeneration.get() != null) {
            try { checkCancelled(); operation.run(); } catch(Exception e) { throw new CompletionException(e); }
            return;
        }
        if (!busy.compareAndSet(false, true)) { say("已有任务执行中；用 /maidhome status 查看或 /maidhome cancel 取消。"); return; }
        cancelRequested = false;
        status = description;
        long token = generation.get();
        worker.execute(() -> {
            jobGeneration.set(token);
            try { checkCancelled(); operation.run(); }
            catch (Exception e) {
                Throwable cause = e; while (cause.getCause() != null) cause = cause.getCause();
                status = cause instanceof CancellationException ? "任务已取消；已完成步骤与备份保留" : description + "失败：" + (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
                LOGGER.debug("{}",description,e);say(status);
            } finally { jobGeneration.remove(); uploading = null; busy.set(false); }
        });
    }
    private <T> T onClient(Supplier<T> task) throws Exception {
        CompletableFuture<T> future = new CompletableFuture<>();
        long token = jobGeneration.get() == null ? generation.get() : jobGeneration.get();
        Minecraft.getInstance().execute(() -> {
            try {
                if (generation.get() != token || Minecraft.getInstance().player == null) throw new IOException("玩家已离开当前世界");
                future.complete(task.get());
            } catch (Throwable e) { future.completeExceptionally(e); }
        });
        return future.get(65, TimeUnit.SECONDS);
    }
    private <T> T onClientAsync(Supplier<CompletableFuture<T>> task) throws Exception {
        return onClient(task).get(65, TimeUnit.SECONDS);
    }
    public void say(String message) {
        LOGGER.info("{}",message);
        String display=message.replace('\n',' ').replace('\r',' ');
        if(display.length()>220)display=display.substring(0,220)+"…";
        history.addLast(java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))+" "+display);
        while(history.size()>40)history.pollFirst();
    }
    private void clickable(String label,String command) {say(label+"；指令："+command);}

    public boolean busy() { return busy.get(); }
    public java.util.List<BridgeNetwork.MaidSummary> ownMaids() { return ownMaids; }
    public boolean removalMatches(UUID maid) { return removal!=null && removal.maid.equals(maid); }
    public void confirmRemovalFromPlatform(JsonObject station,UUID maid) {
        if(!removalMatches(maid)) { say("此台上的目标与已提交迁移不一致"); return; }
        platformTask(station,"maid_move",maid,this::confirmRemoval);
    }
    public boolean connected() { return portal != null && portal.connected(); }
    private Path endpointFile(){return net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("maidhome_bridge-client.properties");}
    private synchronized void loadEndpoint() {
        if(endpointLoaded)return;endpointLoaded=true;
        try{var saved=EndpointSettings.load(endpointFile());endpointHost=saved.host();endpointPort=saved.port();}
        catch(IOException e){LOGGER.warn("无法读取连接设置，使用本机默认地址",e);}
    }
    public synchronized void configureEndpoint(String host,int port)throws IOException {
        var settings=new EndpointSettings(host,port);settings.save(endpointFile());
        endpointHost=settings.host();endpointPort=settings.port();endpointLoaded=true;
    }
    public void reconnect(String host,int port) {
        if(busy()||incoming!=null&&incoming.active()){say("任务仍在进行，请先断开并等待任务结束，再重连");return;}
        try{configureEndpoint(host,port);}catch(Exception e){say(e.getMessage());return;}
        disconnect();connect(endpointHost(),endpointPort());
    }
    public String endpointHost() {loadEndpoint();return endpointHost;}
    public int endpointPort() {loadEndpoint();return endpointPort;}
    public String taskStatus() { return status; }
    public String waitingMode() { return waitMode; }
    public boolean hasReceipt() { return incoming != null && incoming.ready() != null; }
    public boolean hasRemoval() { return removal != null; }
    public java.util.List<String> history() { return java.util.List.copyOf(history); }
    public java.util.List<String> records() { return diskRecords; }
    public com.google.gson.JsonArray remoteList(String kind) { return remoteLists.getOrDefault(kind,new com.google.gson.JsonArray()).deepCopy(); }
    public java.util.List<String> sounds() { return java.util.List.copyOf(com.github.tartaricacid.touhoulittlemaid.client.sound.CustomSoundLoader.CACHE.keySet()); }
    public boolean ownsTask(JsonObject station) {
        JsonObject active=activePlatform;
        return active!=null && BridgeNetwork.str(active,"station").equals(BridgeNetwork.str(station,"station"));
    }
    public void readRecords() {
        TransferStore current=store;
        if(current==null) { diskRecords=java.util.List.of("连接世界后可查看该世界与玩家的传输记录。"); return; }
        CompletableFuture.runAsync(() -> {
            try(var files=java.nio.file.Files.list(current.root().resolve("records"))) {
                var entries=files.filter(p->p.toString().endsWith(".json")).sorted(java.util.Comparator.comparingLong((Path p)->p.toFile().lastModified()).reversed()).limit(200).toList();
                var result=new java.util.ArrayList<String>();
                for(Path p:entries) {
                    if(java.nio.file.Files.size(p)>1024*1024) continue;
                    var j=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(p)).getAsJsonObject();
                    result.add(p.getFileName()+" | "+BridgeNetwork.str(j,"state")+" | "+BridgeNetwork.str(j,"kind")+" | "+BridgeNetwork.str(j,"unity_id"));
                    result.add("  "+p);
                    if(j.has("error")) result.add("  "+BridgeNetwork.str(j,"error"));
                    if(j.has("message")) result.add("  "+BridgeNetwork.str(j,"message"));
                }
                diskRecords=result.isEmpty()?java.util.List.of("暂无传输记录"):java.util.List.copyOf(result);
            } catch(Exception e) { diskRecords=java.util.List.of("记录读取失败："+e.getMessage()); }
        });
    }
    private com.google.gson.JsonArray queryList(String kind) throws IOException {
        JsonObject request=PortalClient.message("list.request"); request.addProperty("kind",kind);
        JsonObject result=requirePortal().request(request,new byte[0]);
        if(!kind.equals(PortalFrame.string(result,"kind")) || !result.has("items") || !result.get("items").isJsonArray()) throw new IOException("列表应答无效");
        var items=result.getAsJsonArray("items").deepCopy(); remoteLists.put(kind,items); return items;
    }
    private PackageUploader uploader() {
        remoteRisk = true;
        return new PackageUploader(requirePortalUnchecked(),store).beforeCommit(() -> {
            checkCancelled();
            if(jobPlatform.get()!=null) {
                try { validatePlatform(); } catch(Exception e) { throw new CompletionException(e); }
            }
        });
    }
    private PortalClient requirePortalUnchecked() {
        try { return requirePortal(); } catch(IOException e) { throw new CompletionException(e); }
    }
    private void validatePlatform() throws Exception {
        JsonObject context=jobPlatform.get(); if(context==null) return;
        JsonObject heartbeat=context.deepCopy(); heartbeat.addProperty("critical",remoteRisk);
        heartbeat.addProperty("receiving",incoming!=null && incoming.active());
        onClientAsync(() -> BridgeNetwork.requestPlatform("pulse",heartbeat));
    }
    public void platformTick() {
        JsonObject active=activePlatform;
        if(active==null || System.nanoTime()<nextPulse || pulse!=null && !pulse.isDone()) return;
        nextPulse=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
        JsonObject data=active.deepCopy(); data.addProperty("critical",remoteRisk); data.addProperty("receiving",incoming!=null && incoming.active());
        pulse=BridgeNetwork.requestPlatform("pulse",data);
        pulse.whenComplete((r,e) -> {
            if(e!=null && activePlatform==active) {
                platformBroken=true; cancelRequested=true;
                PackageUploader upload=uploading; if(upload!=null)upload.cancel();
                if(net.neoforged.fml.ModList.get().isLoaded("minetomesh")) Minecraft.getInstance().execute(HouseExporter::cancel);
                say("传输台任务绑定已失效；停止后续操作，请核对两端结果与记录。");
            }
        });
    }
    private void platformTask(JsonObject station,String kind,UUID maid,Operation operation) {
        run("传输台任务",() -> {
            JsonObject context=station.deepCopy(); context.addProperty("kind",kind);
            if(maid!=null)context.addProperty("maid",maid.toString());
            var accepted=onClientAsync(() -> BridgeNetwork.requestPlatform("begin",context));
            context.addProperty("task",BridgeNetwork.str(accepted,"task"));
            platformBroken=false; remoteRisk=false; unsafeResult=false; activePlatform=context; jobPlatform.set(context);
            String outcome="success", detail="任务完成；请查看结果与记录";
            try {
                if(!connected())connect(endpointHost,endpointPort);
                validatePlatform(); checkCancelled(); operation.run();
                detail=status;
            } catch(Exception e) {
                Throwable root=e; while(root.getCause()!=null)root=root.getCause();
                boolean uncertain=unsafeResult || uploading!=null && uploading.resultUncertain();
                outcome=uncertain?"uncertain":root instanceof CancellationException?"cancelled":"error";
                detail=uncertain?"结果待确认，请核对 MaidHome、MC 实体与本地记录；不要盲目重试":String.valueOf(root.getMessage());
                if(kind.startsWith("receive_")) {
                    rejectIncoming=true;
                    var receipt=incoming==null?null:incoming.ready();
                    if(receipt!=null && connected()) {
                        try { portal.acknowledge(receipt.id(),false,detail); incoming.clear(); } catch(IOException ignored) {}
                    }
                }
                say(detail); throw e;
            } finally {
                waitMode="";
                JsonObject end=context.deepCopy(); end.addProperty("outcome",outcome); end.addProperty("detail",detail);
                try { onClientAsync(() -> BridgeNetwork.requestPlatform("finish",end)); }
                catch(Exception e) { say("无法确认传输台收尾；服务端租约会释放临时占用。请核对两端与记录。"); }
                if(activePlatform==context)activePlatform=null;
                jobPlatform.remove();
            }
        });
    }
    public void sendFromPlatform(JsonObject station,UUID maid,boolean migrate) {
        platformTask(station,"maid_move",maid,() -> exportMaid(true));
    }
    public void soundFromPlatform(JsonObject station,String soundId) {
        platformTask(station,"sound",null,() -> { requireTransferIdle(); uploadSoundPackage(soundId); });
    }
    public void originalSoundFromPlatform(JsonObject station) { platformTask(station,"sound",null,this::uploadSound); }
    public void houseFromPlatform(JsonObject station,String name) {
        platformTask(station,"house",null,() -> uploadHouse(name));
    }
    public void enableReceiver(JsonObject station,long revision,boolean replace,java.util.function.Consumer<JsonObject> response) {
        run("启用接收端",()->{
            if(!connected())connect(endpointHost,endpointPort);
            JsonObject j=station.deepCopy();j.addProperty("revision",revision);j.addProperty("replace",replace);
            JsonObject result=onClientAsync(()->BridgeNetwork.requestPlatform("receiver_enable",j));
            onClient(()->{response.accept(result);return null;});
            if(!result.has("replace_required")){status="接收端已启用，等待 MaidHome 发送";say(status);}
        });
    }
    public void disableReceiver(JsonObject station) {
        run("停用接收端",()->{onClientAsync(()->BridgeNetwork.requestPlatform("receiver_disable",station));status="接收端已停用";say(status);});
    }
    private CompletableFuture<JsonObject> beginReceiver(long token) {
        CompletableFuture<JsonObject> result=new CompletableFuture<>();
        Minecraft.getInstance().execute(()->{
            if(generation.get()!=token){result.completeExceptionally(new IOException("世界已变化"));return;}
            BridgeNetwork.requestPlatform("receiver_begin",new JsonObject()).whenComplete((context,error)->{
                if(error!=null)result.completeExceptionally(error);
                else if(generation.get()!=token)result.completeExceptionally(new IOException("世界已变化"));
                else {activePlatform=context;platformBroken=false;remoteRisk=false;result.complete(context);}
            });
        });return result;
    }
    private void finishReceiver(CompletableFuture<JsonObject> binding,String outcome,String detail) {
        if(binding==null)return;
        binding.thenAccept(context->Minecraft.getInstance().execute(()->{
            JsonObject end=context.deepCopy();end.addProperty("outcome",outcome);end.addProperty("detail",detail==null?"接收失败":detail);
            BridgeNetwork.requestPlatform("finish",end);if(activePlatform==context)activePlatform=null;
        }));
    }
    private void processArrival(PortalClient client,IncomingTransfer receiving,IncomingTransfer.Receipt receipt,CompletableFuture<JsonObject> binding,long token,String mode) {
        run("接收女仆",()->{
            String outcome="error",detail="接收失败";unsafeResult=false;ackWriteStarted=false;
            try {
                if(generation.get()!=token)throw new IOException("世界已变化");
                if(!mode.equals("import")){receive(mode);outcome="cancelled";detail="备份已保留，MaidHome 女仆未移除";status=detail;return;}
                if(binding==null)throw new IOException("没有有效接收端");
                JsonObject context=binding.get(65,TimeUnit.SECONDS);jobPlatform.set(context);activePlatform=context;
                checkCancelled();validateArchive(receipt.archive());validatePlatform();receive("import");
                outcome="success";detail="女仆已恢复到接收台";status=detail;say(detail);
            } catch(Exception e) {
                Throwable cause=e;while(cause.getCause()!=null)cause=cause.getCause();detail=String.valueOf(cause.getMessage());
                if(unsafeResult)outcome="uncertain";
                if(ackWriteStarted)client.close();
                else if(receiving.ready()!=null&&client.connected()){client.acknowledge(receipt.id(),false,"接收未完成："+detail);receiving.clear();}
                throw e;
            } finally {finishReceiver(binding,outcome,detail);jobPlatform.remove();}
        });
    }
}
