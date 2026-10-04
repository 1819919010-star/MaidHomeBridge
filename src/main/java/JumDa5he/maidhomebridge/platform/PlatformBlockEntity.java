package JumDa5he.maidhomebridge.platform;

import java.util.*;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

public final class PlatformBlockEntity extends BlockEntity {
    public UUID identity = UUID.randomUUID();
    public UUID operator;
    public boolean uncertain;
    public String detail = "";
    public long errorUntil;
    public PlatformBlockEntity(BlockPos pos, BlockState state) { super(PlatformContent.ENTITY.get(),pos,state); }
    public List<EntityMaid> occupants() {
        if (level==null) return List.of();
        return level.getEntitiesOfClass(EntityMaid.class,new AABB(worldPosition).inflate(.25,1,.25),
                m -> m.isAlive() && PlatformGeometry.onPad(m.getBoundingBox(),worldPosition,getBlockState().getValue(TransferPlatformBlock.FACING)));
    }
    public void visual(TransferPlatformBlock.Visual state) {
        if (level!=null && !level.isClientSide && level.getBlockState(worldPosition).is(PlatformContent.BLOCK.get())
                && level.getBlockEntity(worldPosition)==this && getBlockState().getValue(TransferPlatformBlock.STATE)!=state)
            level.setBlock(worldPosition,getBlockState().setValue(TransferPlatformBlock.STATE,state),3);
        setChanged();
    }
    public static void tick(Level level, BlockPos pos, BlockState state, PlatformBlockEntity p) {
        if (level.getGameTime()%10!=0) return;
        PlatformService.tick(p);
        if (PlatformService.active(p)) return;
        p.visual(p.uncertain || level.getGameTime()<p.errorUntil ? TransferPlatformBlock.Visual.ERROR :
                p.occupants().size()==1 ? TransferPlatformBlock.Visual.READY : TransferPlatformBlock.Visual.IDLE);
    }
    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag,registries); tag.putUUID("Station",identity);
        if (operator!=null) tag.putUUID("Operator",operator);
        tag.putBoolean("Uncertain",uncertain || PlatformService.active(this)); tag.putString("Detail",detail);
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag,registries); if(tag.hasUUID("Station"))identity=tag.getUUID("Station");
        operator=tag.hasUUID("Operator")?tag.getUUID("Operator"):null; uncertain=tag.getBoolean("Uncertain"); detail=tag.getString("Detail");
    }
    @Override public void setRemoved() { PlatformService.invalidate(this); super.setRemoved(); }
    @Override public void onChunkUnloaded() { PlatformService.invalidate(this); super.onChunkUnloaded(); }
}
