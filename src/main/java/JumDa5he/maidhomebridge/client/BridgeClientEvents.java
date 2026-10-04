package JumDa5he.maidhomebridge.client;

import JumDa5he.maidhomebridge.MaidHomeBridge;
import JumDa5he.maidhomebridge.client.house.HouseExporter;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import java.util.UUID;

@EventBusSubscriber(modid = MaidHomeBridge.MOD_ID, value = Dist.CLIENT)
public final class BridgeClientEvents {
    private static final BridgeClientService SERVICE = BridgeClientService.INSTANCE;
    private BridgeClientEvents() {}
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        if (ModList.get().isLoaded("minetomesh")) HouseExporter.cancel();
        SERVICE.logout();
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        SERVICE.platformTick();
        if (ModList.get().isLoaded("minetomesh")) HouseExporter.tick();
    }

    @SubscribeEvent public static void commands(RegisterClientCommandsEvent event) {
        var root = Commands.literal("maidhome").executes(c -> { help(); return 1; });
        root.then(Commands.literal("help").executes(c -> { help(); return 1; }));
        root.then(Commands.literal("connect").executes(c -> { SERVICE.connect("127.0.0.1",7411); return 1; })
                .then(Commands.argument("host", StringArgumentType.string())
                        .executes(c -> { SERVICE.connect(StringArgumentType.getString(c,"host"),7411); return 1; })
                        .then(Commands.argument("port", IntegerArgumentType.integer(1,65535))
                                .executes(c -> { SERVICE.connect(StringArgumentType.getString(c,"host"),IntegerArgumentType.getInteger(c,"port")); return 1; }))));
        root.then(Commands.literal("disconnect").executes(c -> {
            if (ModList.get().isLoaded("minetomesh")) HouseExporter.cancel();
            SERVICE.disconnect(); return 1;
        }));
        root.then(Commands.literal("status").executes(c -> { SERVICE.status(); return 1; }));
        root.then(Commands.literal("cancel").executes(c -> {
            if (ModList.get().isLoaded("minetomesh")) HouseExporter.cancel();
            SERVICE.cancel(); return 1;
        }));
        root.then(Commands.literal("export").executes(c -> { SERVICE.exportMaid(false); return 1; }));
        root.then(Commands.literal("migrate").executes(c -> { SERVICE.exportMaid(true); return 1; }));
        root.then(Commands.literal("sound").executes(c -> { SERVICE.uploadSound(); return 1; }));
        if (ModList.get().isLoaded("minetomesh")) {
            root.then(Commands.literal("house").then(Commands.argument("name", StringArgumentType.greedyString())
                    .executes(c -> { SERVICE.uploadHouse(StringArgumentType.getString(c,"name")); return 1; })));
        }
        root.then(Commands.literal("maids").executes(c -> { SERVICE.maids(); return 1; }));
        root.then(Commands.literal("select").executes(c -> {
            if (Minecraft.getInstance().hitResult instanceof EntityHitResult hit) SERVICE.select(hit.getEntity().getUUID());
            else SERVICE.say("请对准女仆，或使用 /maidhome maids 点击选择。");
            return 1;
        }).then(Commands.argument("uuid",StringArgumentType.word()).executes(c -> {
            try { SERVICE.select(UUID.fromString(StringArgumentType.getString(c,"uuid"))); }
            catch (IllegalArgumentException e) { SERVICE.say("UUID 无效，请从 /maidhome maids 列表选择。"); }
            return 1;
        })));
        var list = Commands.literal("list");
        for (String kind : new String[]{"maid","house","sound"}) list.then(Commands.literal(kind).executes(c -> { SERVICE.list(kind); return 1; }));
        root.then(list);
        var receive = Commands.literal("receive");
        for (String mode : new String[]{"save","import","reject"}) receive.then(Commands.literal(mode).executes(c -> { SERVICE.receive(mode); return 1; }));
        root.then(receive);
        root.then(Commands.literal("confirm_remove").executes(c -> { SERVICE.confirmRemoval(); return 1; }));
        event.getDispatcher().register(root);
    }
    public static void help() {
        SERVICE.say("连接：/maidhome connect [主机] [端口]；状态／断开：status / disconnect");
        SERVICE.say("选女仆：maids 查看并点击，或 select 对准的女仆；list maid|house|sound 查询 Unity");
        SERVICE.say("上传副本：export；真实迁移：migrate 上传后还需 confirm_remove；音效：sound");
        SERVICE.say("返回：在 Unity 背包内点发送，再 receive save|import|reject；cancel 取消当前任务");
        if (ModList.get().isLoaded("minetomesh")) SERVICE.say("房屋：用 MineToMesh 导出杖完成选区后 /maidhome house 房屋名称");
    }
}
