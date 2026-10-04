package JumDa5he.maidhomebridge.platform;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

public final class PlatformMenu extends AbstractContainerMenu {
    public final BlockPos pos;
    public final UUID station;
    public PlatformMenu(int id, Inventory inventory, BlockPos pos, UUID station) {
        super(PlatformContent.MENU.get(),id); this.pos=pos.immutable(); this.station=station;
    }
    @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
    @Override public boolean stillValid(Player player) {
        return player.distanceToSqr(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5)<=64
                && player.level().getBlockEntity(pos) instanceof PlatformBlockEntity p && p.identity.equals(station);
    }
}
