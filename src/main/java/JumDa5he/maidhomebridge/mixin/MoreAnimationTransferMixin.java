package JumDa5he.maidhomebridge.mixin;

import JumDa5he.maidhomebridge.platform.PlatformService;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.List;

/** MoreAnimation 1.3.0rc updates idle animations outside the entity tick. */
@Pseudo
@Mixin(targets="com.github.JumDa5he.moreanimation.compat.event.MaidInteractionEvent", remap=false)
public abstract class MoreAnimationTransferMixin {
    @Inject(method="loadedMaids", at=@At("RETURN"), cancellable=true, require=0)
    private static void maidhome$skipTransferring(ServerLevel level, CallbackInfoReturnable<List<? extends EntityMaid>> cir) {
        cir.setReturnValue(cir.getReturnValue().stream().filter(m->!PlatformService.frozen(m)).toList());
    }
}
