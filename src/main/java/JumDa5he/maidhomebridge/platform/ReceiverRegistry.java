package JumDa5he.maidhomebridge.platform;

import com.google.gson.JsonObject;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import java.io.IOException;
import java.util.UUID;

/** One persisted receiver per server save, shared across all dimensions and players. */
public final class ReceiverRegistry extends SavedData {
    private static final Factory<ReceiverRegistry> FACTORY=new Factory<>(ReceiverRegistry::new,ReceiverRegistry::load,null);
    private boolean enabled;
    private String dimension="";
    private BlockPos pos=BlockPos.ZERO;
    private UUID station,owner;
    private long revision;
    public static ReceiverRegistry get(MinecraftServer server) { return server.overworld().getDataStorage().computeIfAbsent(FACTORY,"maidhome_receiver"); }
    public static ReceiverRegistry load(CompoundTag tag,HolderLookup.Provider provider) {
        ReceiverRegistry r=new ReceiverRegistry();r.enabled=tag.getBoolean("Enabled");r.dimension=tag.getString("Dimension");r.pos=BlockPos.of(tag.getLong("Pos"));
        r.station=tag.hasUUID("Station")?tag.getUUID("Station"):null;r.owner=tag.hasUUID("Owner")?tag.getUUID("Owner"):null;r.revision=tag.getLong("Revision");
        if(r.station==null||r.owner==null)r.enabled=false;return r;
    }
    @Override public CompoundTag save(CompoundTag tag,HolderLookup.Provider provider) {
        tag.putBoolean("Enabled",enabled);tag.putString("Dimension",dimension);tag.putLong("Pos",pos.asLong());tag.putLong("Revision",revision);
        if(station!=null)tag.putUUID("Station",station);if(owner!=null)tag.putUUID("Owner",owner);return tag;
    }
    public boolean matches(PlatformBlockEntity p) { return enabled&&station.equals(p.identity)&&p.getBlockPos().equals(pos)&&p.getLevel()!=null&&p.getLevel().dimension().location().toString().equals(dimension); }
    public boolean ownedBy(ServerPlayer p) { return enabled&&p.getUUID().equals(owner); }
    public PlatformBlockEntity resolve(MinecraftServer server) {
        if(!enabled)return null;
        ResourceLocation id=ResourceLocation.tryParse(dimension);
        var level=id==null?null:server.getLevel(ResourceKey.create(Registries.DIMENSION,id));
        if(level==null){clear();return null;}
        // Unloaded is unavailable, not destroyed. Never force-load a remote chunk to receive.
        if(!level.hasChunkAt(pos))return null;
        if(level.getBlockEntity(pos) instanceof PlatformBlockEntity p&&matches(p)&&!p.isRemoved())return p;
        clear();return null;
    }
    public JsonObject describe(MinecraftServer server) {
        PlatformBlockEntity p=resolve(server);JsonObject j=new JsonObject();j.addProperty("enabled",enabled);j.addProperty("available",p!=null);j.addProperty("revision",revision);
        if(enabled){j.addProperty("dimension",dimension);j.addProperty("x",pos.getX());j.addProperty("y",pos.getY());j.addProperty("z",pos.getZ());j.addProperty("station",station.toString());j.addProperty("owner",owner.toString());}
        return j;
    }
    public JsonObject enable(ServerPlayer player,PlatformBlockEntity p,long expected,boolean replace) throws IOException {
        PlatformBlockEntity old=resolve(player.getServer());
        if(expected!=revision)throw new IOException("接收端已变化，请重新确认");
        if(enabled&&!matches(p)&&!replace){JsonObject j=describe(player.getServer());j.addProperty("replace_required",true);return j;}
        if(PlatformService.active(p)||(old!=null&&PlatformService.active(old)))throw new IOException("传输正在进行，请结束后切换接收端");
        if(p.uncertain)throw new IOException("此台有结果待确认的任务，请先核对记录");
        enabled=true;dimension=p.getLevel().dimension().location().toString();pos=p.getBlockPos().immutable();station=p.identity;owner=player.getUUID();revision++;setDirty();
        if(old!=null&&old!=p)old.visual(old.occupants().size()==1?TransferPlatformBlock.Visual.READY:TransferPlatformBlock.Visual.IDLE);
        p.visual(TransferPlatformBlock.Visual.READY);return describe(player.getServer());
    }
    public void disable(ServerPlayer player,PlatformBlockEntity p) throws IOException {
        if(!matches(p))throw new IOException("当前台不是接收端");
        if(!ownedBy(player)&&!player.hasPermissions(2))throw new IOException("只有启用者或管理员可以停用接收端");
        if(PlatformService.active(p))throw new IOException("正在传输，请结束后停用");
        clear();p.visual(p.occupants().size()==1?TransferPlatformBlock.Visual.READY:TransferPlatformBlock.Visual.IDLE);
    }
    public void clear() { enabled=false;revision++;setDirty(); }
    public static JsonObject context(PlatformBlockEntity p) {
        JsonObject j=new JsonObject();j.addProperty("dimension",p.getLevel().dimension().location().toString());j.addProperty("x",p.getBlockPos().getX());j.addProperty("y",p.getBlockPos().getY());j.addProperty("z",p.getBlockPos().getZ());j.addProperty("station",p.identity.toString());return j;
    }
}
