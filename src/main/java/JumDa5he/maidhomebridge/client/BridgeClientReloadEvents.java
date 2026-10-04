package JumDa5he.maidhomebridge.client;

import JumDa5he.maidhomebridge.MaidHomeBridge;
import JumDa5he.maidhomebridge.client.house.HouseExporter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import java.util.concurrent.CompletableFuture;

@EventBusSubscriber(modid = MaidHomeBridge.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class BridgeClientReloadEvents {
    private BridgeClientReloadEvents() {}
    @SubscribeEvent public static void register(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((barrier, resources, prepareProfiler, applyProfiler, prepareExecutor, gameExecutor) -> {
            BridgeClientService.INSTANCE.resourceReloaded();
            return CompletableFuture.runAsync(() -> {
                if (ModList.get().isLoaded("minetomesh")) HouseExporter.cancel();
            }, gameExecutor).thenCompose(barrier::wait);
        });
    }
}
