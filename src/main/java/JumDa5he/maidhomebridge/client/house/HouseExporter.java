package JumDa5he.maidhomebridge.client.house;

import com.nebysse.minetomesh.content.MineToMeshContent;
import com.nebysse.minetomesh.job.DefaultExportPipeline;
import com.nebysse.minetomesh.job.ExportJob;
import com.nebysse.minetomesh.job.ExportOptions;
import com.nebysse.minetomesh.job.ExportTelemetry;
import com.nebysse.minetomesh.job.JobState;
import com.nebysse.minetomesh.network.WandRequestPolicy;
import com.nebysse.minetomesh.output.ExportName;
import com.nebysse.minetomesh.wand.ExportWandSelection;
import com.nebysse.minetomesh.world.Selection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Loaded only after checking the optional mod. The caller must obtain server export permission. */
public final class HouseExporter {
    private static final ExecutorService FILES = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "MaidHome-house-files"); thread.setDaemon(true); return thread;
    });
    private static Operation active;
    private HouseExporter() {}

    public static boolean isAvailable() { return ModList.get().isLoaded("minetomesh"); }

    public static CompletableFuture<Path> export(String name, Path destination, Consumer<String> progress) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) return CompletableFuture.failedFuture(new IllegalStateException("房屋导出必须从客户端线程启动"));
        if (active != null) return CompletableFuture.failedFuture(new IOException("已有房屋导出正在进行"));
        try {
            if (!isAvailable()) throw new IOException("房屋功能需要 MineToMesh 1.2.0");
            if (mc.player == null || mc.level == null) throw new IOException("请先进入世界");
            if (name == null || name.isBlank() || name.length() > 128) throw new IOException("房屋名称需为 1~128 字符");
            ExportWandSelection wand = selection(mc.player.getMainHandItem());
            if (wand == null) wand = selection(mc.player.getOffhandItem());
            if (wand == null) throw new IOException("请手持 MineToMesh 导出魔杖，并先设置 POS1/POS2");
            if (!WandRequestPolicy.validateDimension(wand, mc.level.dimension().location()).accepted())
                throw new IOException("MineToMesh 魔杖选区与当前维度不符");
            if (!WandRequestPolicy.validateSelection(wand, mc.level.getMinBuildHeight(), mc.level.getMaxBuildHeight()).accepted())
                throw new IOException("MineToMesh 魔杖选区不完整或超出世界高度");
            Selection selection = wand.toSelection().orElseThrow();
            WalkabilityScanner scanner = new WalkabilityScanner(mc.level,
                    new BlockPos(selection.min().x(), selection.min().y(), selection.min().z()),
                    Math.toIntExact(selection.sizeX()), Math.toIntExact(selection.sizeY()), Math.toIntExact(selection.sizeZ()));
            active = new Operation(mc.level, selection, wand.includePlayers(), name, destination, progress, scanner);
            progress.accept("正在检查房屋碰撞与站立空间；请保持建筑不变");
            return active.future;
        } catch (Exception e) { return CompletableFuture.failedFuture(e); }
    }

    private static ExportWandSelection selection(ItemStack stack) {
        return stack.is(MineToMeshContent.EXPORT_WAND_ITEM.get())
                ? stack.get(MineToMeshContent.EXPORT_WAND_SELECTION.get()) : null;
    }

    public static void tick() {
        Operation operation = active;
        if (operation == null) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            if (operation.cancelled || mc.level != operation.level || operation.future.isCancelled())
                throw new IOException("房屋导出已取消或世界已切换");
            if (System.nanoTime() - operation.started > Duration.ofMinutes(15).toNanos())
                throw new IOException("房屋导出超过 15 分钟，请缩小选区重试");
            if (operation.packing) {
                if (operation.future.isDone()) active = null;
                return;
            }
            if (operation.job == null) {
                if (!operation.scanner.tick()) { operation.progress.accept("房屋站立空间检查 " + operation.scanner.percent() + "%"); return; }
                operation.scanner.metadata(operation.name, operation.model);
                operation.job = DefaultExportPipeline.create(mc, operation.selection, ExportName.parse(operation.model),
                        new ExportOptions(operation.includePlayers), new ExportTelemetry());
                return;
            }
            operation.job.tick();
            if (operation.job.state() == JobState.COMPLETED) {
                Path exported = operation.job.finalDirectory().orElseThrow();
                var metadata = operation.scanner.metadata(operation.name, operation.model);
                var report = operation.scanner.report();
                report.addProperty("minetomesh_warnings", operation.job.warningCount());
                operation.progress.accept("MineToMesh 导出完成，正在收集全部模型、纹理、报告（导出警告 "
                        + operation.job.warningCount() + "）；整数站立网格采用保守碰撞判断");
                operation.packing = true;
                FILES.execute(() -> {
                    try { operation.future.complete(HousePackage.collect(exported, operation.destination, operation.model,
                            metadata, report, () -> operation.cancelled)); }
                    catch (Exception e) { operation.future.completeExceptionally(e); }
                });
            } else if (operation.job.isTerminal()) {
                throw new IOException("MineToMesh 导出失败：" + operation.job.failureReason().orElse(operation.job.state().toString()));
            } else operation.progress.accept("MineToMesh 房屋导出 " + operation.job.progress().percent() + "%（"
                    + operation.job.progress().stageKey() + "）");
        } catch (Exception e) {
            operation.cancelled = true;
            if (operation.job != null && !operation.job.isTerminal()) operation.job.cancel("maidhome_bridge_cancelled");
            operation.future.completeExceptionally(e); active = null;
        }
    }

    public static void cancel() {
        Minecraft.getInstance().execute(() -> {
            Operation operation = active;
            if (operation == null) return;
            operation.cancelled = true;
            if (operation.job != null && !operation.job.isTerminal()) operation.job.cancel("maidhome_bridge_cancelled");
            operation.future.completeExceptionally(new IOException("房屋导出已取消，已完成的原始导出保留"));
            active = null;
        });
    }

    private static final class Operation {
        final ClientLevel level;
        final Selection selection;
        final boolean includePlayers;
        final String name, model = "house_" + UUID.randomUUID().toString().replace("-", "");
        final Path destination;
        final Consumer<String> progress;
        final WalkabilityScanner scanner;
        final CompletableFuture<Path> future = new CompletableFuture<>();
        final long started = System.nanoTime();
        volatile boolean cancelled;
        boolean packing;
        ExportJob job;
        Operation(ClientLevel level, Selection selection, boolean includePlayers, String name,
                  Path destination, Consumer<String> progress, WalkabilityScanner scanner) {
            this.level = level; this.selection = selection; this.includePlayers = includePlayers;
            this.name = name; this.destination = destination; this.progress = progress; this.scanner = scanner;
        }
    }
}
