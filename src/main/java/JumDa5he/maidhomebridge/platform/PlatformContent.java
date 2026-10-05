package JumDa5he.maidhomebridge.platform;

import JumDa5he.maidhomebridge.MaidHomeBridge;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.*;

public final class PlatformContent {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MaidHomeBridge.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MaidHomeBridge.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MaidHomeBridge.MOD_ID);
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, MaidHomeBridge.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MaidHomeBridge.MOD_ID);
    public static final DeferredBlock<TransferPlatformBlock> BLOCK = BLOCKS.register("transfer_platform", TransferPlatformBlock::new);
    public static final DeferredItem<net.minecraft.world.item.BlockItem> ITEM = ITEMS.registerSimpleBlockItem(BLOCK);
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("maidhome", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.maidhome_bridge"))
            .icon(() -> ITEM.get().getDefaultInstance())
            .withTabsBefore(CreativeModeTabs.FUNCTIONAL_BLOCKS)
            .displayItems((parameters, output) -> output.accept(ITEM.get()))
            .build());
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PlatformBlockEntity>> ENTITY = ENTITIES.register("transfer_platform",
            () -> BlockEntityType.Builder.of(PlatformBlockEntity::new, BLOCK.get()).build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<PlatformMenu>> MENU = MENUS.register("transfer_platform",
            () -> IMenuTypeExtension.create((id, inventory, data) -> new PlatformMenu(id, inventory, data.readBlockPos(), data.readUUID())));
    private PlatformContent() {}
    public static void register(IEventBus bus) {
        BLOCKS.register(bus); ITEMS.register(bus); ENTITIES.register(bus); MENUS.register(bus); TABS.register(bus);
    }
}
