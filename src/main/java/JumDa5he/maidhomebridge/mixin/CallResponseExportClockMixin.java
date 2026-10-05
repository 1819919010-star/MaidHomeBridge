package JumDa5he.maidhomebridge.mixin;

import JumDa5he.maidhomebridge.platform.PlatformService;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Re-read real addon state, using the same clock; never reuse a cached addon snapshot. */
@Pseudo
@Mixin(targets="com.github.JumDa5he.callresponse.compat.migration.CallResponseNpcEventProvider", remap=false)
public abstract class CallResponseExportClockMixin {
    @Redirect(method="export", at=@At(value="INVOKE", target="Lnet/minecraft/world/level/Level;getGameTime()J"), require=0)
    private long maidhome$exportTime(Level level, EntityMaid maid) { return PlatformService.snapshotTime(maid,false); }
    @Redirect(method="export", at=@At(value="INVOKE", target="Lnet/minecraft/world/level/Level;getDayTime()J"), require=0)
    private long maidhome$exportDay(Level level, EntityMaid maid) { return PlatformService.snapshotTime(maid,true); }
}
