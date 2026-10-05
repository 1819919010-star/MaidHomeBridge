package JumDa5he.maidhomebridge.server;

import JumDa5he.maidhomebridge.platform.PlatformContent;
import JumDa5he.maidhomebridge.platform.PlatformGeometry;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

@EventBusSubscriber
public class EventHandler {
    @SubscribeEvent
    public static void onUse(PlayerInteractEvent.RightClickBlock event){
        if(!event.getLevel().getBlockState(event.getPos()).is(PlatformContent.BLOCK))return;
        if(!(event.getEntity().getFirstPassenger() instanceof EntityMaid maid))return;
        maid.stopRiding();
        maid.setPos(event.getPos().getBottomCenter().add(0, PlatformGeometry.SURFACE, 0));
        maid.setInSittingPose(true);
        event.setCanceled(true);
    }
}
