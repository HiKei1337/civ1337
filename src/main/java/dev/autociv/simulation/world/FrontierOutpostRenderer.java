package dev.autociv.simulation.world;

import dev.autociv.simulation.model.BorderOutpost;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.server.level.ServerLevel;

/** Builds small camps only where every touched chunk is already loaded and the site is clear. */
public final class FrontierOutpostRenderer {
    private static final int HALF_SIZE = 4;

    private FrontierOutpostRenderer() { }

    public static int materializeLoaded(ServerLevel level, WorldSimulation simulation) {
        int built = 0;
        for (BorderOutpost outpost : simulation.borderOutposts()) {
            if (materialize(level, outpost)) built++;
        }
        return built;
    }

    private static boolean materialize(ServerLevel level, BorderOutpost outpost) {
        int[][] offsets = {{0, 0}, {8, 0}, {-8, 0}, {0, 8}, {0, -8},
                {16, 0}, {-16, 0}, {0, 16}, {0, -16}};
        for (int[] offset : offsets) {
            if (buildAt(level, outpost.x() + offset[0], outpost.z() + offset[1])) return true;
        }
        return false;
    }

    private static boolean buildAt(ServerLevel level, int centerX, int centerZ) {
        int minChunkX = Math.floorDiv(centerX - HALF_SIZE, 16);
        int maxChunkX = Math.floorDiv(centerX + HALF_SIZE, 16);
        int minChunkZ = Math.floorDiv(centerZ - HALF_SIZE, 16);
        int maxChunkZ = Math.floorDiv(centerZ + HALF_SIZE, 16);
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (!level.hasChunkAt(new BlockPos(chunkX * 16, level.getMinBuildHeight(), chunkZ * 16))) return false;
            }
        }

        BlockPos center = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                new BlockPos(centerX, 0, centerZ));
        BlockPos gatePosition = new BlockPos(centerX, center.getY(), centerZ + HALF_SIZE);
        if (level.getBlockState(center).is(Blocks.CAMPFIRE)
                && level.getBlockState(gatePosition).is(Blocks.OAK_FENCE_GATE)) return true;
        int baseY = center.getY();
        for (int x = centerX - HALF_SIZE; x <= centerX + HALF_SIZE; x++) {
            for (int z = centerZ - HALF_SIZE; z <= centerZ + HALF_SIZE; z++) {
                BlockPos floor = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        new BlockPos(x, 0, z));
                if (floor.getY() != baseY || !level.getFluidState(floor.below()).isEmpty()
                        || level.getBlockState(floor.below()).getCollisionShape(level, floor.below()).isEmpty()) {
                    return false;
                }
                for (int height = 0; height < 4; height++) {
                    BlockPos space = floor.above(height);
                    if (!level.getFluidState(space).isEmpty() || !level.getBlockState(space).isAir()) {
                        return false;
                    }
                }
            }
        }

        for (int offset = -HALF_SIZE; offset <= HALF_SIZE; offset++) {
            place(level, centerX + offset, baseY, centerZ - HALF_SIZE, Blocks.OAK_FENCE.defaultBlockState());
            if (offset != 0) {
                place(level, centerX + offset, baseY, centerZ + HALF_SIZE, Blocks.OAK_FENCE.defaultBlockState());
            }
            if (offset != -HALF_SIZE && offset != HALF_SIZE) {
                place(level, centerX - HALF_SIZE, baseY, centerZ + offset, Blocks.OAK_FENCE.defaultBlockState());
                place(level, centerX + HALF_SIZE, baseY, centerZ + offset, Blocks.OAK_FENCE.defaultBlockState());
            }
        }
        for (int[] corner : new int[][]{{-4, -4}, {-4, 4}, {4, -4}, {4, 4}}) {
            for (int height = 0; height < 3; height++) {
                place(level, centerX + corner[0], baseY + height, centerZ + corner[1],
                        Blocks.OAK_LOG.defaultBlockState());
            }
        }
        place(level, centerX, baseY, centerZ, Blocks.CAMPFIRE.defaultBlockState());
        place(level, centerX, baseY, centerZ + HALF_SIZE, Blocks.OAK_FENCE_GATE.defaultBlockState());
        return true;
    }

    private static void place(ServerLevel level, int x, int y, int z,
                              net.minecraft.world.level.block.state.BlockState state) {
        level.setBlockAndUpdate(new BlockPos(x, y, z), state);
    }
}
