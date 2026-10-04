package JumDa5he.maidhomebridge.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class PlatformGeometry {
    public static final double SURFACE = 5.75 / 16.0;
    private static final double[][] BOXES = {
            {0,0,0,16,5,16}, {3,5,4,13,5.75,12}, {4,5,3,12,5.75,4}, {4,5,12,12,5.75,13},
            {0,0,0,3.5,6,3.5}, {12.5,0,0,16,6,3.5}, {0,0,12.35,3.65,14.75,16}, {12.5,0,12.5,16,6,16}
    };
    private PlatformGeometry() {}
    public static int turns(Direction direction) {
        return switch (direction) { case EAST -> 1; case SOUTH -> 2; case WEST -> 3; default -> 0; };
    }
    public static AABB rotate(AABB box, int turns) {
        for (int i = 0; i < turns; i++) box = new AABB(1-box.maxZ,box.minY,box.minX,1-box.minZ,box.maxY,box.maxX);
        return box;
    }
    public static VoxelShape shape(Direction direction) {
        VoxelShape shape = Shapes.empty();
        for (double[] b : BOXES) shape = Shapes.or(shape, Shapes.create(rotate(new AABB(b[0]/16,b[1]/16,b[2]/16,b[3]/16,b[4]/16,b[5]/16), turns(direction))));
        return shape.optimize();
    }
    public static boolean onPad(AABB feet, BlockPos pos, Direction facing) {
        if (Math.abs(feet.minY - pos.getY() - SURFACE) > 0.075) return false;
        AABB local = feet.move(-pos.getX(), -pos.getY(), -pos.getZ());
        AABB pad = rotate(new AABB(.25,0,.25,.75,1,.75), turns(facing));
        double cx=(local.minX+local.maxX)/2, cz=(local.minZ+local.maxZ)/2;
        return cx >= pad.minX-.05 && cx <= pad.maxX+.05 && cz >= pad.minZ-.05 && cz <= pad.maxZ+.05
                && local.maxX > pad.minX && local.minX < pad.maxX && local.maxZ > pad.minZ && local.minZ < pad.maxZ;
    }
}
