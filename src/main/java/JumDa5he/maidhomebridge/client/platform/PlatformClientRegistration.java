package JumDa5he.maidhomebridge.client.platform;

import JumDa5he.maidhomebridge.MaidHomeBridge;
import JumDa5he.maidhomebridge.platform.PlatformContent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(modid=MaidHomeBridge.MOD_ID, value=Dist.CLIENT)
public final class PlatformClientRegistration {
    private PlatformClientRegistration() {}
    @SubscribeEvent public static void screens(RegisterMenuScreensEvent event) { event.register(PlatformContent.MENU.get(),PlatformScreen::new); }
}
