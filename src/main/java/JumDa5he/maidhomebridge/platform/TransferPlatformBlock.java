package JumDa5he.maidhomebridge.platform;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.*;
import net.minecraft.network.chat.Component;

public final class TransferPlatformBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<Visual> STATE = EnumProperty.create("state", Visual.class);
    public static final MapCodec<TransferPlatformBlock> CODEC = simpleCodec(p -> new TransferPlatformBlock());
    private static final VoxelShape[] SHAPES = java.util.Arrays.stream(new Direction[]{Direction.NORTH,Direction.EAST,Direction.SOUTH,Direction.WEST})
            .map(PlatformGeometry::shape).toArray(VoxelShape[]::new);
    public enum Visual implements net.minecraft.util.StringRepresentable {
        IDLE(2), READY(6), TRANSFERRING(12), ERROR(7);
        public final int light;
        Visual(int light) { this.light=light; }
        @Override public String getSerializedName() { return name().toLowerCase(java.util.Locale.ROOT); }
    }
    public TransferPlatformBlock() {
        super(Properties.of().strength(2.5F).sound(SoundType.METAL).noOcclusion().lightLevel(s -> s.getValue(STATE).light));
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(STATE, Visual.IDLE));
    }
    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(FACING,STATE); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext c) { return defaultBlockState().setValue(FACING,c.getHorizontalDirection().getOpposite()); }
    @Override protected BlockState rotate(BlockState s, Rotation r) { return s.setValue(FACING,r.rotate(s.getValue(FACING))); }
    @Override protected BlockState mirror(BlockState s, Mirror m) { return s.rotate(m.getRotation(s.getValue(FACING))); }
    @Override protected RenderShape getRenderShape(BlockState s) { return RenderShape.MODEL; }
    @Override protected VoxelShape getShape(BlockState s, BlockGetter l, BlockPos p, CollisionContext c) { return SHAPES[PlatformGeometry.turns(s.getValue(FACING))]; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new PlatformBlockEntity(pos,state); }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level l, BlockState s, BlockEntityType<T> type) {
        return l.isClientSide ? null : createTickerHelper(type, PlatformContent.ENTITY.get(), PlatformBlockEntity::tick);
    }
    @Override protected void onRemove(BlockState state,Level level,BlockPos pos,BlockState replacement,boolean moving) {
        if(!state.is(replacement.getBlock())&&!level.isClientSide&&level.getBlockEntity(pos) instanceof PlatformBlockEntity platform) {
            var registry=ReceiverRegistry.get(((net.minecraft.server.level.ServerLevel)level).getServer());
            if(registry.matches(platform))registry.clear();
        }
        super.onRemove(state,level,pos,replacement,moving);
    }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer server && level.getBlockEntity(pos) instanceof PlatformBlockEntity platform)
            server.openMenu(new SimpleMenuProvider((id,inv,p) -> new PlatformMenu(id,inv,pos,platform.identity),
                    Component.translatable("block.maidhome_bridge.transfer_platform")), b -> { b.writeBlockPos(pos); b.writeUUID(platform.identity); });
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
