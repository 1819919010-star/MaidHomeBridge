package JumDa5he.maidhomebridge;

import JumDa5he.maidhomebridge.network.BridgeNetwork;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

@Mod(MaidHomeBridge.MOD_ID)
public final class MaidHomeBridge {
    public static final String MOD_ID = "maidhome_bridge";
    public MaidHomeBridge(IEventBus events) {
        JumDa5he.maidhomebridge.platform.PlatformContent.register(events);
        BridgeNetwork.register(events);
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> BridgeNetwork.clearServerState());
    }
}
