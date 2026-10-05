package JumDa5he.maidhomebridge.platform;

import JumDa5he.maidhomebridge.MaidHomeBridge;
import JumDa5he.maidhomebridge.network.BridgeNetwork;
import JumDa5he.maidhomebridge.server.ServerBridge;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonObject;
import io.github.zgxhzhr.maidfm.data.MaidFileData;
import io.github.zgxhzhr.maidfm.service.MaidTransferService;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import java.io.IOException;
import java.util.*;

@EventBusSubscriber(modid=MaidHomeBridge.MOD_ID)
public final class PlatformService {
    private static final Map<UUID, Session> TASKS = new HashMap<>();
    private static final String LOCK = "MaidHomePlatformOriginalNoAI";
    private static final ThreadLocal<SpawnContext> SPAWN = new ThreadLocal<>();
    private PlatformService() {}
    public static final class Session {
        public final UUID id=UUID.randomUUID(), player, station;
        public final PlatformBlockEntity platform;
        public final String kind;
        public final EntityMaid maid;
        public final String model;
        public final long created, dayTime;
        public long expires;
        public boolean critical, removed, receiving;
        Session(ServerPlayer p, PlatformBlockEntity b, String kind, EntityMaid maid) {
            this.player=p.getUUID(); this.platform=b; station=b.identity; this.kind=kind; this.maid=maid;
            model=maid==null?"":maid.getModelId(); created=p.level().getGameTime(); dayTime=p.level().getDayTime(); expires=created+200;
        }
    }
    private record SpawnContext(Session session, List<EntityMaid> added) {}
    public static JsonObject handle(ServerPlayer player, String operation, JsonObject data) throws IOException {
        ReceiverRegistry registry=ReceiverRegistry.get(player.getServer());
        if(operation.equals("receiver_state"))return registry.describe(player.getServer());
        if(operation.equals("receiver_begin")) {
            PlatformBlockEntity receiver=registry.resolve(player.getServer());
            if(receiver==null)throw new IOException("没有已启用且已加载的接收台");
            if(!registry.ownedBy(player))throw new IOException("当前接收端由另一位玩家启用，请由启用者连接 MaidHome");
            if(receiver.getLevel()!=player.level())throw new IOException("请回到接收台所在维度再接收");
            JsonObject context=ReceiverRegistry.context(receiver);context.addProperty("receiver",true);context.addProperty("kind","receive_import");
            JsonObject result=handle(player,"begin",context);context.addProperty("task",result.get("task").getAsString());return context;
        }
        PlatformBlockEntity p = platform(player,data);
        switch (operation) {
            case "status" -> { return describe(player,p); }
            case "receiver_enable" -> {return registry.enable(player,p,data.get("revision").getAsLong(),data.has("replace")&&data.get("replace").getAsBoolean());}
            case "receiver_disable" -> {registry.disable(player,p);return registry.describe(player.getServer());}
            case "begin" -> {
                String kind=BridgeNetwork.str(data,"kind");
                if(data.has("receiver")&&!kind.equals("receive_import"))throw new IOException("接收凭据不能用于发送");
                if(!Set.of("maid_copy","maid_move","sound","house","receive_save","receive_import","receive_manual").contains(kind)) throw new IOException("任务类型无效");
                if(p.uncertain) throw new IOException("此台上次任务结果待确认，请先核对两端和记录");
                if(active(p) || TASKS.values().stream().anyMatch(s->s.player.equals(player.getUUID()))) throw new IOException("此传输台或玩家已有任务");
                EntityMaid maid=null;
                if(kind.startsWith("maid_")) {
                    var list=p.occupants();
                    if(list.size()!=1) throw new IOException(list.isEmpty()?"请将一名女仆带到台面中央":"台上有多名女仆，请只保留一名");
                    maid=list.getFirst();
                    if(!maid.getUUID().toString().equals(BridgeNetwork.str(data,"maid"))) throw new IOException("台上目标已经变化，请重新确认");
                    if(!maid.isOwnedBy(player)) throw new IOException("只有女仆主人可以发送");
                    if(maid.isYsmModel()) throw new IOException("不支持 YSM 模型");
                    if(maid.isPassenger() || maid.isVehicle()) throw new IOException("请先让女仆解除骑乘，再站上传输台");
                    if(locked(maid)!=null) throw new IOException("女仆正在另一项任务中");
                    if(kind.equals("maid_move")) ServerBridge.requireEmptyForMigration(maid);
                }
                if(kind.equals("house") && (!ModList.get().isLoaded("minetomesh") || !houseAllowed(player))) throw new IOException("需要 MineToMesh 及房屋导出权限");
                if(kind.startsWith("receive_")&&(!registry.matches(p)||!registry.ownedBy(player)))throw new IOException("请先启用当前传输台为接收端");
                if(kind.equals("receive_import")) checkSpace(p,null);
                Session task=new Session(player,p,kind,maid);
                if(maid!=null) {
                    maid.getPersistentData().putBoolean(LOCK,maid.isNoAi());
                    maid.getNavigation().stop(); maid.setNoAi(true);
                }
                TASKS.put(task.id,task); p.operator=player.getUUID(); p.detail="";
                if(!kind.startsWith("receive_")) p.visual(TransferPlatformBlock.Visual.TRANSFERRING);
                p.setChanged(); JsonObject result=describe(player,p); result.addProperty("task",task.id.toString()); return result;
            }
            case "pulse" -> {
                Session task=require(player,data); validateTarget(task);
                task.expires=player.level().getGameTime()+200;
                task.critical |= data.has("critical") && data.get("critical").getAsBoolean();
                task.receiving |= data.has("receiving") && data.get("receiving").getAsBoolean();
                if(!task.kind.startsWith("receive_") || task.receiving) p.visual(TransferPlatformBlock.Visual.TRANSFERRING);
                return describe(player,p);
            }
            case "finish" -> {
                Session task=require(player,data);
                String outcome=BridgeNetwork.str(data,"outcome");
                if(!Set.of("success","cancelled","error","uncertain").contains(outcome)) throw new IOException("任务结果无效");
                end(task,outcome,BridgeNetwork.str(data,"detail")); return describe(player,p);
            }
            case "acknowledge" -> {
                if(active(p)) throw new IOException("任务仍在进行");
                if(p.operator!=null && !p.operator.equals(player.getUUID()) && !player.hasPermissions(2)) throw new IOException("只有原操作者可以确认此结果");
                p.uncertain=false; p.errorUntil=0; p.setChanged(); return describe(player,p);
            }
            case "selection" -> {
                if(!ModList.get().isLoaded("minetomesh")) throw new IOException("需要安装 MineToMesh");
                if(!houseAllowed(player)) throw new IOException("服务器不允许房屋选区操作");
                if(active(p)) throw new IOException("传输期间不能修改选区");
                return PlatformHouseService.selection(player,data);
            }
            default -> throw new IOException("未知传输台操作");
        }
    }
    public static boolean houseAllowed(ServerPlayer p) { return p.getServer()!=null && (p.getServer().isSingleplayer() || p.hasPermissions(2)); }
    public static PlatformBlockEntity platform(ServerPlayer player, JsonObject data) throws IOException {
        if(!player.isAlive() || !player.serverLevel().dimension().location().toString().equals(BridgeNetwork.str(data,"dimension"))) throw new IOException("世界或维度已变化");
        BlockPos pos=new BlockPos(data.get("x").getAsInt(),data.get("y").getAsInt(),data.get("z").getAsInt());
        boolean receiving=data.has("receiver")&&data.get("receiver").getAsBoolean();
        if((!receiving&&player.distanceToSqr(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5)>64) || !player.serverLevel().hasChunkAt(pos)) throw new IOException("距离传输台太远或区块未加载");
        if(!(player.level().getBlockEntity(pos) instanceof PlatformBlockEntity p) || !p.identity.toString().equals(BridgeNetwork.str(data,"station"))) throw new IOException("传输台已失效或被替换");
        if(receiving&&(!ReceiverRegistry.get(player.getServer()).matches(p)||!ReceiverRegistry.get(player.getServer()).ownedBy(player)))throw new IOException("接收端已停用或更换");
        return p;
    }
    public static Session require(ServerPlayer player, JsonObject data) throws IOException {
        PlatformBlockEntity p=platform(player,data);
        UUID id;
        try { id=UUID.fromString(BridgeNetwork.str(data,"task")); } catch(Exception e) { throw new IOException("缺少传输台任务凭据"); }
        Session s=TASKS.get(id);
        if(s==null || s.platform!=p || !s.player.equals(player.getUUID())) throw new IOException("传输台任务已结束或不属于你");
        return s;
    }
    public static void authorizeMaid(ServerPlayer p, JsonObject metadata, EntityMaid maid) throws IOException {
        Session lock=locked(maid);
        if(metadata.has("platform")) {
            Session s=require(p,metadata.getAsJsonObject("platform")); validateTarget(s);
            if(s.maid!=maid || !s.kind.startsWith("maid_")) throw new IOException("任务目标不匹配");
        } else if(lock!=null) throw new IOException("此女仆正在传输台任务中，请先结束该任务");
    }
    public static MaidFileData snapshot(ServerPlayer p, EntityMaid maid) {
        boolean held=maid.getPersistentData().contains(LOCK), old=held && maid.getPersistentData().getBoolean(LOCK);
        if(held) { maid.setNoAi(old); maid.getPersistentData().remove(LOCK); }
        try { return MaidTransferService.exportMaidToData(p,maid.getId()); }
        finally { if(held) { maid.getPersistentData().putBoolean(LOCK,old); maid.setNoAi(true); } }
    }
    public static void removed(EntityMaid maid) { Session s=locked(maid); if(s!=null) s.removed=true; }
    public static void playTransferSound(EntityMaid maid) {
        // Cosmetic feedback must never turn an already completed transfer into a failure.
        try { maid.level().playSound(null, maid.getX(), maid.getY(), maid.getZ(),
                net.minecraft.sounds.SoundEvents.PORTAL_TRIGGER, net.minecraft.sounds.SoundSource.BLOCKS, 1.0F, 1.0F); }
        catch(RuntimeException e) { org.slf4j.LoggerFactory.getLogger("MaidHomeBridge").warn("传输已完成，但传送门音效播放失败",e); }
    }
    public static void validateTarget(Session s) throws IOException {
        if(s.maid==null || s.removed) return;
        var list=s.platform.occupants();
        if(!s.maid.isAlive() || s.maid.level()!=s.platform.getLevel() || list.size()!=1 || list.getFirst()!=s.maid
                || !s.player.equals(s.maid.getOwnerUUID()) || s.maid.isYsmModel() || !s.model.equals(s.maid.getModelId()))
            throw new IOException("目标离台、模型变化或台上出现多名女仆，已停止任务");
    }
    private static Session locked(EntityMaid maid) { return TASKS.values().stream().filter(s->s.maid==maid).findFirst().orElse(null); }
    /** Server-thread only. An idle occupant is never frozen; the existing lease owns recovery. */
    public static boolean frozen(net.minecraft.world.entity.Entity entity) {
        return !entity.level().isClientSide && entity instanceof EntityMaid maid && locked(maid)!=null;
    }
    /** Use one clock origin when an addon serializes relative timers during a transfer. */
    public static long snapshotTime(EntityMaid maid, boolean day) {
        Session s=maid.level().isClientSide?null:locked(maid);
        return s==null ? (day?maid.level().getDayTime():maid.level().getGameTime()) : (day?s.dayTime:s.created);
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public static void freezeTick(net.neoforged.neoforge.event.tick.EntityTickEvent.Pre e) {
        if(frozen(e.getEntity())) e.setCanceled(true);
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public static void freezeDamage(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent e) {
        if(frozen(e.getEntity())) e.setCanceled(true);
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public static void freezeInteract(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract e) {
        if(frozen(e.getTarget())) { e.setCancellationResult(net.minecraft.world.InteractionResult.FAIL); e.setCanceled(true); }
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST)
    public static void freezeInteractAt(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteractSpecific e) {
        if(frozen(e.getTarget())) { e.setCancellationResult(net.minecraft.world.InteractionResult.FAIL); e.setCanceled(true); }
    }
    public static boolean active(PlatformBlockEntity p) { return TASKS.values().stream().anyMatch(s->s.platform==p); }
    public static void tick(PlatformBlockEntity p) {
        for(Session s:List.copyOf(TASKS.values())) if(s.platform==p) {
            long now=p.getLevel().getGameTime();
            long limit=s.kind.startsWith("receive_") && !s.receiving ? 6000 : 36000;
            var player=((ServerLevel)p.getLevel()).getServer().getPlayerList().getPlayer(s.player);
            try {
                if(player==null || player.level()!=p.getLevel() || !player.isAlive() || now>s.expires || now-s.created>limit) throw new IOException("任务超时或玩家离开，需核对两端结果");
                validateTarget(s);
                if(s.maid!=null && !s.removed) { s.maid.getNavigation().stop(); s.maid.setNoAi(true); }
            } catch(IOException e) { end(s,s.critical?"uncertain":"error",e.getMessage()); }
        }
    }
    private static void end(Session s,String outcome,String detail) {
        TASKS.remove(s.id);
        if(s.maid!=null) restore(s.maid);
        PlatformBlockEntity p=s.platform; p.uncertain=outcome.equals("uncertain"); p.detail=detail.substring(0,Math.min(1024,detail.length()));
        p.errorUntil=p.getLevel()==null?0:p.getLevel().getGameTime()+60;
        if(!outcome.equals("error")&&!p.uncertain) p.errorUntil=0;
        if(p.detaching)return; // Never access or mark a chunk while it is unloading.
        p.visual(outcome.equals("error")||p.uncertain?TransferPlatformBlock.Visual.ERROR:p.occupants().size()==1||isReceiver(p)?TransferPlatformBlock.Visual.READY:TransferPlatformBlock.Visual.IDLE);
    }
    public static void invalidate(PlatformBlockEntity p) {
        if(p.getLevel()==null || p.getLevel().isClientSide) return;
        for(Session s:List.copyOf(TASKS.values())) if(s.platform==p) end(s,s.critical?"uncertain":"error","传输台被破坏或区块卸载；请核对备份与两端结果");
    }
    public static void clear() { for(Session s:List.copyOf(TASKS.values())) end(s,s.critical?"uncertain":"cancelled","服务器关闭"); }
    private static void restore(EntityMaid maid) {
        if(maid.getPersistentData().contains(LOCK)) { maid.setNoAi(maid.getPersistentData().getBoolean(LOCK)); maid.getPersistentData().remove(LOCK); }
    }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent e) {
        for(Session s:List.copyOf(TASKS.values())) if(s.player.equals(e.getEntity().getUUID())) end(s,s.critical?"uncertain":"cancelled","玩家离线，任务已停止");
    }
    @SubscribeEvent(priority=EventPriority.HIGHEST) public static void join(EntityJoinLevelEvent e) {
        if(e.getLevel().isClientSide || !(e.getEntity() instanceof EntityMaid maid)) return;
        if(locked(maid)==null) restore(maid);
        SpawnContext context=SPAWN.get();
        if(context==null || !context.added.isEmpty() || context.session.platform.getLevel()!=e.getLevel()) return;
        PlatformBlockEntity p=context.session.platform; BlockPos pos=p.getBlockPos();
        // Real maid widths can graze the corner terminal at the exact model center.
        // Search a few nearby pad centers, rotating the preference with the terminal.
        double[][] points={{.5,.5},{.54,.46},{.54,.5},{.5,.46},{.58,.42},{.58,.5},{.5,.42},{.62,.38}};
        // Corner lights reach 6/16, slightly above the central 5.75/16 pad.
        // A standard 0.6-wide maid can rest on those lights; never sink its feet into them.
        for(double height:new double[]{PlatformGeometry.SURFACE,6.0/16.0}) for(double[] point:points) {
            double x=point[0],z=point[1];
            for(int i=0;i<PlatformGeometry.turns(p.getBlockState().getValue(TransferPlatformBlock.FACING));i++) {double old=x;x=1-z;z=old;}
            maid.setPos(pos.getX()+x,pos.getY()+height,pos.getZ()+z);
            try { checkSpace(p,maid); context.added.add(maid); return; }
            catch(IOException ignored) { }
        }
        e.setCanceled(true);
    }
    public record Arrival(io.github.zgxhzhr.maidfm.data.ImportResult result,EntityMaid maid) {}
    public static Arrival importAt(ServerPlayer player, JsonObject metadata, MaidFileData data) throws IOException {
        Session s;
        try {
            s=require(player,metadata);
            if(!ReceiverRegistry.get(player.getServer()).matches(s.platform)||!ReceiverRegistry.get(player.getServer()).ownedBy(player))throw new IOException("接收端已停用或更换");
            if(!s.kind.equals("receive_import") && !s.kind.equals("receive_manual")) throw new IOException("当前台未选择导入模式");
            checkSpace(s.platform,null);
        } catch(IOException e) {return new Arrival(io.github.zgxhzhr.maidfm.data.ImportResult.failed(net.minecraft.network.chat.Component.literal(e.getMessage())),null);}
        s.receiving=true; s.critical=true; s.platform.visual(TransferPlatformBlock.Visual.TRANSFERRING);
        SpawnContext ctx=new SpawnContext(s,new ArrayList<>()); SPAWN.set(ctx);
        try {
            var result=MaidTransferService.importMaidFromData(player,data,true);
            if(result.spawned() && ctx.added.isEmpty()) throw new IOException("前置报告导入成功，但未确认台面实体，结果待确认");
            EntityMaid added=ctx.added.isEmpty()?null:ctx.added.getFirst();
            if(result.spawned()&&(added==null||!added.isAlive()||player.serverLevel().getEntity(added.getUUID())!=added))throw new IOException("未确认实体进入世界，结果待确认");
            if(result.spawned()) {
                playTransferSound(added);
                try {added.getChatBubbleManager().addChatBubble(com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.implement.TextChatBubbleData.type2(net.minecraft.network.chat.Component.literal("主人我回来了，另一个世界也很有趣呢")));}
                catch(RuntimeException e){org.slf4j.LoggerFactory.getLogger("MaidHomeBridge").warn("女仆已恢复，但欢迎气泡显示失败",e);}
            }
            return new Arrival(result,result.spawned()?added:null);
        } finally { SPAWN.remove(); }
    }
    private static void checkSpace(PlatformBlockEntity p, EntityMaid maid) throws IOException {
        BlockPos pos=p.getBlockPos();
        AABB box=maid==null?new AABB(pos.getX()+.25,pos.getY()+PlatformGeometry.SURFACE+.001,pos.getZ()+.25,pos.getX()+.75,pos.getY()+PlatformGeometry.SURFACE+1.8,pos.getZ()+.75):maid.getBoundingBox().deflate(.001);
        if(!p.occupants().isEmpty() || !p.getLevel().noCollision(maid,box) || !p.getLevel().getEntities(maid,box,e->e.isAlive() && e.isPickable()).isEmpty()) throw new IOException("台面或头部空间被占用，无法导入");
    }
    private static JsonObject describe(ServerPlayer player,PlatformBlockEntity p) {
        JsonObject j=new JsonObject(); var occupants=p.occupants(); j.addProperty("count",occupants.size()); j.addProperty("uncertain",p.uncertain);
        j.add("receiver",ReceiverRegistry.get(player.getServer()).describe(player.getServer()));j.addProperty("receiver_here",isReceiver(p));
        j.addProperty("active",active(p)); j.addProperty("detail",p.detail); j.addProperty("houseAllowed",houseAllowed(player));
        j.addProperty("state",p.getBlockState().getValue(TransferPlatformBlock.STATE).getSerializedName());
        if(occupants.size()==1) {
            EntityMaid m=occupants.getFirst(); j.addProperty("maid",m.getUUID().toString()); j.addProperty("name",m.getName().getString());
            j.addProperty("model",m.getModelId()); j.addProperty("owner",m.getOwner()==null?String.valueOf(m.getOwnerUUID()):m.getOwner().getName().getString());
            j.addProperty("eligible",m.isOwnedBy(player)&&!m.isYsmModel()); j.addProperty("sound",m.getSoundPackId());
            String reason=m.isYsmModel()?"不支持 YSM 模型":!m.isOwnedBy(player)?"你不是女仆主人":"";
            j.addProperty("reason",reason);
        }
        return j;
    }
    public static boolean isReceiver(PlatformBlockEntity p) {return p.getLevel() instanceof ServerLevel level&&ReceiverRegistry.get(level.getServer()).matches(p);}
}
