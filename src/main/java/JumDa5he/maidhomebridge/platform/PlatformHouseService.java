package JumDa5he.maidhomebridge.platform;

import com.google.gson.JsonObject;
import com.nebysse.minetomesh.content.MineToMeshContent;
import com.nebysse.minetomesh.wand.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import java.io.IOException;

/** Loaded only when the optional MineToMesh mod is present. */
public final class PlatformHouseService {
    private PlatformHouseService() {}
    public static JsonObject selection(ServerPlayer player,JsonObject request) throws IOException {
        ItemStack stack=player.getMainHandItem().is(MineToMeshContent.EXPORT_WAND_ITEM.get())?player.getMainHandItem():player.getOffhandItem();
        if(!stack.is(MineToMeshContent.EXPORT_WAND_ITEM.get())) throw new IOException("请在主手或副手持有 MineToMesh 导出杖");
        var service=ExportWandService.INSTANCE;
        if(request.has("first")) {
            BlockPos first=parse(request.get("first").getAsString()), second=parse(request.get("second").getAsString());
            long dx=Math.abs((long)first.getX()-second.getX())+1,dy=Math.abs((long)first.getY()-second.getY())+1,dz=Math.abs((long)first.getZ()-second.getZ())+1;
            if(dx>1048576 || dy>1048576 || dz>1048576) throw new IOException("选区边长过大");
            long volume=dx*dy*dz;
            if(volume>1048576 || volume<=0) throw new IOException("选区体积超过 1,048,576 格");
            if(first.getY()<player.level().getMinBuildHeight() || second.getY()<player.level().getMinBuildHeight() || first.getY()>=player.level().getMaxBuildHeight() || second.getY()>=player.level().getMaxBuildHeight()) throw new IOException("选区高度越界");
            var current=service.selection(stack);
            if(current.selectionDimension().isPresent() && !current.selectionDimension().get().equals(player.level().dimension().location())) throw new IOException("导出杖属于其他维度，请先使用导出杖清空选区");
            service.setEndpoint(stack,player.level().dimension().location(),Endpoint.POS1,first,player.level().getMinBuildHeight(),player.level().getMaxBuildHeight());
            service.setEndpoint(stack,player.level().dimension().location(),Endpoint.POS2,second,player.level().getMinBuildHeight(),player.level().getMaxBuildHeight());
            if(request.has("includePlayers")) service.setIncludePlayers(stack,request.get("includePlayers").getAsBoolean());
            player.containerMenu.broadcastChanges();
            player.inventoryMenu.broadcastChanges();
        }
        var selection=service.selection(stack); JsonObject out=new JsonObject();
        out.addProperty("first",selection.pos1().map(PlatformHouseService::format).orElse(""));
        out.addProperty("second",selection.pos2().map(PlatformHouseService::format).orElse(""));
        out.addProperty("includePlayers",selection.includePlayers());
        out.addProperty("complete",selection.isComplete());
        return out;
    }
    private static BlockPos parse(String value) throws IOException {
        try {
            String[] a=value.trim().split("[ ,，]+"); if(a.length!=3)throw new IllegalArgumentException();
            int x=Integer.parseInt(a[0]),y=Integer.parseInt(a[1]),z=Integer.parseInt(a[2]);
            if(Math.abs((long)x)>29999984 || Math.abs((long)z)>29999984 || Math.abs((long)y)>4096)throw new IllegalArgumentException();
            return new BlockPos(x,y,z);
        } catch(Exception e) { throw new IOException("坐标需要三个有效整数：X Y Z"); }
    }
    private static String format(BlockPos p) { return p.getX()+" "+p.getY()+" "+p.getZ(); }
}
