package JumDa5he.maidhomebridge.client.house;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Integer-height, 0.6-wide standing positions; unsupported partial surfaces remain blocked. */
final class WalkabilityScanner {
    static final int MAX_CELLS = 1_048_576;
    private static final double EPSILON = 1.0E-5;
    private final ClientLevel level;
    private final BlockPos min;
    private final int sx, sy, sz;
    private final char[][] layers;
    private int cursor, count;
    private int[] origin;
    private long bestDistance = Long.MAX_VALUE;

    WalkabilityScanner(ClientLevel level, BlockPos min, int sx, int sy, int sz) throws IOException {
        this.level = level; this.min = min.immutable(); this.sx = sx; this.sy = sy; this.sz = sz;
        if (sx <= 0 || sy <= 0 || sz <= 0 || (long) sx * sy * sz > MAX_CELLS)
            throw new IOException("房屋选区上限为 " + MAX_CELLS + " 格，请缩小 MineToMesh 选区");
        layers = new char[sy][sz * (sx + 1) - 1];
        for (char[] layer : layers) {
            java.util.Arrays.fill(layer, '0');
            for (int z = 0; z < sz - 1; z++) layer[z * (sx + 1) + sx] = '\n';
        }
    }

    boolean tick() throws IOException {
        long until = System.nanoTime() + 3_000_000L;
        int processed = 0;
        while (cursor < sx * sy * sz && processed++ < 1024 && System.nanoTime() < until) {
            int x = cursor % sx, z = cursor / sx % sz, y = cursor / (sx * sz); cursor++;
            if (!isWalkable(x, y, z)) continue;
            layers[y][z * (sx + 1) + x] = '1'; count++;
            long dx = 2L * x + 1 - sx, dz = 2L * z + 1 - sz;
            long score = dx * dx + dz * dz;
            if (score < bestDistance || score == bestDistance && (origin == null || y < origin[1])) {
                origin = new int[]{x, y, z}; bestDistance = score;
            }
        }
        return cursor == sx * sy * sz;
    }

    private boolean isWalkable(int lx, int ly, int lz) throws IOException {
        // Support and headroom must belong to the exported selection, including its floor.
        if (ly == 0 || ly + 1.8 > sy) return false;
        BlockPos foot = min.offset(lx, ly, lz);
        int x = foot.getX(), y = foot.getY(), z = foot.getZ();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (!level.hasChunk((x + dx) >> 4, (z + dz) >> 4))
                throw new IOException("选区及碰撞边界有未加载区块，请靠近选区后重试");
        }
        for (int dy = -1; dy <= 1; dy++) {
            var state = level.getBlockState(foot.offset(0, dy, 0));
            if (!state.getFluidState().isEmpty() || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                    || state.is(Blocks.CACTUS) || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CAMPFIRE)
                    || state.is(Blocks.SOUL_CAMPFIRE) || state.is(Blocks.SWEET_BERRY_BUSH)
                    || state.is(Blocks.POWDER_SNOW)) return false;
        }
        AABB body = new AABB(x + .2, y + EPSILON, z + .2, x + .8, y + 1.8, z + .8);
        for (VoxelShape collision : level.getBlockCollisions(null, body)) {
            if (!collision.isEmpty()) return false;
        }
        VoxelShape requiredSupport = Shapes.create(new AABB(x + .2, y - EPSILON, z + .2,
                x + .8, y, z + .8));
        VoxelShape support = Shapes.empty();
        for (VoxelShape collision : level.getBlockCollisions(null, requiredSupport.bounds()))
            support = Shapes.or(support, collision);
        return !Shapes.joinIsNotEmpty(requiredSupport, support, BooleanOp.ONLY_FIRST);
    }

    int percent() { return (int) (100L * cursor / (sx * sy * sz)); }

    JsonObject metadata(String name, String model) throws IOException {
        if (origin == null) throw new IOException("选区没有可表达的安全站立点：需包含地板与至少 1.8 格净空；半格高度采用保守阻挡");
        JsonObject json = new JsonObject();
        JsonArray size = new JsonArray(); size.add(sx); size.add(sy); size.add(sz);
        JsonArray spawn = new JsonArray(); for (int coordinate : origin) spawn.add(coordinate);
        JsonArray walkable = new JsonArray(); for (char[] layer : layers) walkable.add(new String(layer));
        json.add("size", size); json.addProperty("name", name); json.add("origin", spawn);
        json.addProperty("model", model); json.add("walkable", walkable);
        return json;
    }

    JsonObject report() {
        JsonObject json = new JsonObject(); json.addProperty("walkable_cells", count);
        json.addProperty("snapshot", "rolling_client_snapshot");
        json.addProperty("origin_meaning", "selection-relative maid spawn cell, selected near horizontal center");
        json.addProperty("standing_width", .6); json.addProperty("standing_height", 1.8);
        List<String> warnings = new ArrayList<>();
        warnings.add("网格只表达整数脚底高度；半砖、楼梯、栏杆等不满足完整支撑面的格子保守标 0。");
        warnings.add("碰撞与模型均为分帧客户端快照；导出期间请保持建筑不变。动态实体不计入静态可行走网格。");
        warnings.add("地板及 1.8 格净空必须完整包含在选区内；液体和已知危险方块保守标 0。");
        JsonArray values = new JsonArray(); warnings.forEach(values::add); json.add("warnings", values);
        return json;
    }
}
