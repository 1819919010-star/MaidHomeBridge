package JumDa5he.maidhomebridge.mixin;

import JumDa5he.maidhomebridge.platform.PlatformService;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets="com.github.JumDa5he.callresponse.compat.hunger.HungerManager", remap=false)
public abstract class CallResponseHungerTransferMixin {
    @Inject(method="lambda$onServerTick$0", at=@At("HEAD"), cancellable=true, require=0)
    private void maidhome$pauseHunger(ServerPlayer player, EntityMaid maid, CallbackInfo ci) {
        if(PlatformService.frozen(maid)) ci.cancel();
    }
}
