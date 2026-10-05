package JumDa5he.maidhomebridge.mixin;

import JumDa5he.maidhomebridge.platform.PlatformService;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets="com.github.JumDa5he.callresponse.compat.emotion.EmotionPassiveManager", remap=false)
public abstract class CallResponseEmotionTransferMixin {
    @Inject(method="lambda$onServerTick$3", at=@At("HEAD"), cancellable=true, require=0)
    private static void maidhome$pauseEmotion(EntityMaid maid, CallbackInfo ci) {
        if(PlatformService.frozen(maid)) ci.cancel();
    }
}
