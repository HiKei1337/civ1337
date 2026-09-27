package dev.autociv.scenario;

import dev.autociv.simulation.model.Settlement;
import dev.autociv.simulation.model.BuildingType;
import dev.autociv.simulation.model.BuildingPlan;
import dev.autociv.simulation.model.Citizen;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Items;

/** Generates one compact settlement entirely inside its naturally loaded chunk. */
public final class HistoricSettlementBuilder {

    private static final int HOME_CLEAR_STEPS = 125;

    private static final int[][] HOME_SITES = {
            {-6, -5}, {6, -5}, {-6, 5}, {6, 5}, {-2, -6}, {2, -6}
    };

    private HistoricSettlementBuilder() {
    }

    public static boolean generateInChunk(ServerLevel level, Settlement settlement, String style) {
        return generateInChunk(level, settlement, style, false);
    }

    /** Charter sites preserve the player-selected center instead of relocating to the chunk center. */
    public static boolean generateAtStoredPosition(ServerLevel level, Settlement settlement, String style) {
        return generateInChunk(level, settlement, style, true);
    }

    /** Rejects player-built structures and liquids across the full one-chunk settlement footprint. */
    public static boolean isNaturalSite(ServerLevel level, int centerX, int centerZ) {
        ChunkPos chunk = new ChunkPos(centerX >> 4, centerZ >> 4);
        int lowestSurface = Integer.MAX_VALUE;
        int highestSurface = Integer.MIN_VALUE;
        for (int x = centerX - 15; x <= centerX + 15; x++) {
            for (int z = centerZ - 15; z <= centerZ + 15; z++) {
                if (!chunk.equals(new ChunkPos(x >> 4, z >> 4))) continue;
                BlockPos probe = new BlockPos(x, level.getMinBuildHeight(), z);
                if (!level.hasChunkAt(probe)) return false;
                int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                lowestSurface = Math.min(lowestSurface, surface);
                highestSurface = Math.max(highestSurface, surface);
                BlockPos ground = new BlockPos(x, surface - 1, z);
                BlockState groundState = level.getBlockState(ground);
                boolean naturalGround = groundState.is(BlockTags.BASE_STONE_OVERWORLD)
                        || groundState.is(Blocks.GRASS_BLOCK) || groundState.is(Blocks.DIRT)
                        || groundState.is(Blocks.COARSE_DIRT) || groundState.is(Blocks.PODZOL)
                        || groundState.is(Blocks.MYCELIUM) || groundState.is(Blocks.SAND)
                        || groundState.is(Blocks.RED_SAND) || groundState.is(Blocks.GRAVEL)
                        || groundState.is(Blocks.SNOW_BLOCK);
                if (!naturalGround || !level.getFluidState(ground).isEmpty()) return false;
                for (int y = surface; y < Math.min(surface + 10, level.getMaxBuildHeight()); y++) {
                    if (!mayReplaceConstructionSite(level.getBlockState(new BlockPos(x, y, z)))) return false;
                }
            }
        }
        return lowestSurface != Integer.MAX_VALUE && highestSurface - lowestSurface <= 4;
    }

    private static boolean generateInChunk(ServerLevel level, Settlement settlement, String style,
                                           boolean preserveCenter) {
        if (settlement.physicalSettlementGenerated()) {
            ensureTownHall(level, settlement, palette(level, settlement.x(), settlement.z(), style), style);
            return false;
        }
        ChunkPos chunk = new ChunkPos(settlement.x() >> 4, settlement.z() >> 4);
        BlockPos anchor = new BlockPos(chunk.getMinBlockX() + 8, 0, chunk.getMinBlockZ() + 8);
        if (!level.hasChunkAt(anchor)) {
            return false;
        }

        level.getChunkAt(anchor);
        int centerX = preserveCenter ? settlement.x() : anchor.getX();
        int centerZ = preserveCenter ? settlement.z() : anchor.getZ();
        if (preserveCenter && (!chunk.equals(new ChunkPos(centerX >> 4, centerZ >> 4))
                || !isNaturalSite(level, centerX, centerZ))) return false;
        int floorY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, centerX, centerZ);
        settlement.setPosition(centerX, floorY, centerZ);
        Palette palette = palette(level, centerX, centerZ, style);
        prepareGround(level, chunk, centerX, floorY, centerZ, palette.foundation());
        buildTownHall(level, chunk, centerX, floorY, centerZ, palette, style);
        dev.autociv.simulation.world.TownHallStorage.ensureChestAtTownHall(level, settlement);
        int starterHuts = Math.clamp(settlement.population(), 1, HOME_SITES.length);
        for (int index = 0; index < starterHuts; index++) {
            int homeX = centerX + HOME_SITES[index][0];
            int homeZ = centerZ + HOME_SITES[index][1];
            if (settlement.population() >= 8) {
                buildHouse(level, chunk, homeX, floorY, homeZ, palette);
            } else {
                buildEarthHut(level, chunk, homeX, floorY, homeZ, palette, style);
            }
        }
        buildFarm(level, chunk, centerX - 1, floorY, centerZ + 4, palette.path());
        buildMine(level, chunk, centerX - 7, floorY, centerZ - 2, palette);
        buildWoodYard(level, chunk, centerX + 4, floorY, centerZ - 2, palette);
        settlement.registerStartingFacility(BuildingType.FARM);
        settlement.registerStartingFacility(BuildingType.MINE);
        int plannedHomes = Math.max(starterHuts, settlement.completedHomes());
        settlement.setHomeBuildingState(plannedHomes, starterHuts, 0);
        settlement.setPhysicalSettlementGenerated(true);
        return true;
    }

    /** One physical construction action. A builder goal calls this at a walking/work cadence. */
    public static boolean buildNextHomeBlock(ServerLevel level, Settlement settlement, String style,
                                             Citizen.Profession residentProfession, String era,
                                             java.util.List<Container> warehouse) {
        if (!settlement.physicalSettlementGenerated()
                || settlement.materializedHomes() >= settlement.completedHomes()) {
            return false;
        }
        int homeIndex = settlement.materializedHomes();
        int[] offset = homeOffset(homeIndex);
        int x = settlement.x() + offset[0];
        int z = settlement.z() + offset[1];
        BlockPos center = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                new BlockPos(x, 0, z));
        if (!level.hasChunkAt(center)) {
            return false;
        }
        int groundY = center.getY();
        int cursor = settlement.homeBuildCursor();
        java.util.List<HomePlacement> placements = homePlacements(x, groundY, z,
                palette(level, x, z, style), selectHomeVariant(settlement, residentProfession, era, homeIndex));
        if (cursor == 0 && !isValidHomeSite(level, x, groundY, z)) return false;
        int totalSteps = HOME_CLEAR_STEPS + placements.size();
        if (cursor < HOME_CLEAR_STEPS) {
            int dx = cursor % 5 - 2;
            int dy = cursor / 25 + 1;
            int dz = cursor / 5 % 5 - 2;
            BlockPos clearPos = new BlockPos(x + dx, groundY + dy, z + dz);
            if (!level.hasChunkAt(clearPos) || !mayReplaceConstructionSite(level.getBlockState(clearPos))) {
                return false;
            }
            set(level, new ChunkPos(clearPos), clearPos.getX(), clearPos.getY(), clearPos.getZ(),
                    Blocks.AIR.defaultBlockState());
        } else {
            int placementIndex = cursor - HOME_CLEAR_STEPS;
            if (placementIndex >= placements.size()) {
                settlement.markNextHomeMaterialized();
                return true;
            }
            HomePlacement placement = placements.get(placementIndex);
            BlockPos placementPos = new BlockPos(placement.x(), placement.y(), placement.z());
            if (!level.hasChunkAt(placementPos)
                    || !mayReplaceConstructionSite(level.getBlockState(placementPos))) {
                return false;
            }
            if (!placeMaterializedBlock(level, placementPos, placement.state(), warehouse)) return false;
        }
        cursor++;
        if (cursor >= totalSteps) {
            settlement.markNextHomeMaterialized();
        } else {
            settlement.setHomeBuildCursor(cursor);
        }
        return true;
    }

    public static BlockPos nextHomeWorkPosition(Settlement settlement) {
        int[] offset = homeOffset(settlement.materializedHomes());
        return new BlockPos(settlement.x() + offset[0], settlement.y(), settlement.z() + offset[1]);
    }

    /** Physical block item required by the current home blueprint step, excluding clearing and fixtures. */
    public static Item nextHomeMaterial(ServerLevel level, Settlement settlement, String style,
                                        Citizen.Profession profession, String era) {
        if (!settlement.physicalSettlementGenerated()
                || settlement.materializedHomes() >= settlement.completedHomes()) return null;
        int[] offset = homeOffset(settlement.materializedHomes());
        int x = settlement.x() + offset[0], z = settlement.z() + offset[1];
        BlockPos center = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
        int cursor = settlement.homeBuildCursor();
        if (!level.hasChunkAt(center) || cursor < HOME_CLEAR_STEPS) return null;
        java.util.List<HomePlacement> placements = homePlacements(x, center.getY(), z,
                palette(level, x, z, style), selectHomeVariant(settlement, profession, era,
                        settlement.materializedHomes()));
        int index = cursor - HOME_CLEAR_STEPS;
        return index >= 0 && index < placements.size()
                ? constructionMaterial(placements.get(index).state()) : null;
    }

    public static BlockPos torchHouseSite(Settlement settlement, int index) {
        int[] offset = homeOffset(Math.max(0, index));
        return new BlockPos(settlement.x() + offset[0], 0, settlement.z() + offset[1] - 2);
    }

    private static int[] homeOffset(int index) {
        if (index < HOME_SITES.length) return HOME_SITES[Math.max(0, index)];
        int additional = index - HOME_SITES.length;
        int ring = additional / 8;
        int slot = additional % 8;
        double radius = 20.0 + ring * 7.0;
        double angle = slot * Math.PI / 4.0 + ring * 0.17;
        return new int[]{(int) Math.round(Math.cos(angle) * radius),
                (int) Math.round(Math.sin(angle) * radius)};
    }

    /** Returns a naturally loaded generated bed for a resident, without requesting chunks. */
    public static BlockPos residentBed(ServerLevel level, Settlement settlement, java.util.UUID citizenId) {
        if (settlement == null || citizenId == null || !settlement.physicalSettlementGenerated()) return null;
        int homes = Math.max(settlement.population(), settlement.materializedHomes());
        if (homes == 0) return null;
        int first = Math.floorMod(citizenId.hashCode(), homes);
        for (int attempt = 0; attempt < homes; attempt++) {
            int index = (first + attempt) % homes;
            int[] offset = homeOffset(index);
            BlockPos bed = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    new BlockPos(settlement.x() + offset[0], 0, settlement.z() + offset[1]));
            if (level.hasChunkAt(bed) && level.getBlockState(bed).is(Blocks.RED_BED)) return bed;
        }
        return null;
    }

    /** Finds and remembers a clear site for the oldest completed facility awaiting placement. */
    public static BlockPos nextFacilityWorkPosition(ServerLevel level, Settlement settlement) {
        BuildingPlan plan = settlement.nextPhysicalBuilding();
        if (plan == null) return null;
        if (!plan.hasSite()) {
            int ordinal = settlement.buildings().values().stream().mapToInt(Integer::intValue).sum()
                    - settlement.pendingPhysicalBuildings().size();
            BlockPos site = findFacilitySite(level, settlement, plan.type(), Math.max(0, ordinal));
            if (site == null) return null;
            plan.assignSite(site.getX(), site.getY(), site.getZ());
        }
        return new BlockPos(plan.siteX(), plan.siteY(), plan.siteZ());
    }

    /** Places one facility-template block. The caller sets the worker cadence and saves the cursor. */
    public static boolean buildNextFacilityBlock(ServerLevel level, Settlement settlement, String style,
                                                 java.util.List<Container> warehouse) {
        BuildingPlan plan = settlement.nextPhysicalBuilding();
        if (plan == null || nextFacilityWorkPosition(level, settlement) == null) return false;
        java.util.List<HomePlacement> placements = facilityPlacements(plan.type(), plan.siteX(), plan.siteY(),
                plan.siteZ(), palette(level, plan.siteX(), plan.siteZ(), style));
        if (plan.blockCursor() >= placements.size()) {
            settlement.markPhysicalBuildingComplete(plan);
            return true;
        }
        HomePlacement placement = placements.get(plan.blockCursor());
        BlockPos position = new BlockPos(placement.x(), placement.y(), placement.z());
        if (!level.hasChunkAt(position) || !mayReplaceConstructionSite(level.getBlockState(position))) return false;
        if (!placeMaterializedBlock(level, position, placement.state(), warehouse)) return false;
        plan.advanceBlock();
        if (plan.blockCursor() >= placements.size()) settlement.markPhysicalBuildingComplete(plan);
        return true;
    }

    /** Physical block item required by the next facility template placement. */
    public static Item nextFacilityMaterial(ServerLevel level, Settlement settlement, String style) {
        BuildingPlan plan = settlement.nextPhysicalBuilding();
        if (plan == null || nextFacilityWorkPosition(level, settlement) == null) return null;
        java.util.List<HomePlacement> placements = facilityPlacements(plan.type(), plan.siteX(), plan.siteY(),
                plan.siteZ(), palette(level, plan.siteX(), plan.siteZ(), style));
        int cursor = plan.blockCursor();
        return cursor >= 0 && cursor < placements.size()
                ? constructionMaterial(placements.get(cursor).state()) : null;
    }

    private static Item constructionMaterial(BlockState state) {
        if (state.isAir()) return null;
        boolean structural = state.is(BlockTags.LOGS) || state.is(BlockTags.PLANKS)
                || state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.STONE_BRICKS)
                || state.is(BlockTags.WOODEN_STAIRS) || state.is(BlockTags.WOODEN_SLABS)
                || state.is(BlockTags.WOODEN_FENCES) || state.is(BlockTags.WOODEN_TRAPDOORS)
                || state.is(BlockTags.STAIRS) || state.is(BlockTags.SLABS)
                || state.is(BlockTags.WALLS);
        if (!structural) return null;
        Item item = state.getBlock().asItem();
        return item instanceof BlockItem && item != Items.AIR ? item : null;
    }

    private static boolean placeMaterializedBlock(ServerLevel level, BlockPos position, BlockState state,
                                                   java.util.List<Container> warehouse) {
        Item required = constructionMaterial(state);
        if (required != null && !dev.autociv.simulation.world.TownHallStorage.hasItem(warehouse, required, 1)) {
            return false;
        }
        BlockState previous = level.getBlockState(position);
        if (!level.setBlock(position, state, 3)) return false;
        if (required != null && !dev.autociv.simulation.world.TownHallStorage.consumeItem(warehouse, required, 1)) {
            level.setBlock(position, previous, 3);
            return false;
        }
        return true;
    }

    private static BlockPos findFacilitySite(ServerLevel level, Settlement settlement, BuildingType type, int ordinal) {
        int[][] offsets = {{0, 18}, {18, 0}, {0, -18}, {-18, 0}, {18, 18}, {-18, 18},
                {18, -18}, {-18, -18}, {30, 0}, {0, 30}, {-30, 0}, {0, -30},
                {30, 18}, {-30, 18}, {30, -18}, {-30, -18}};
        for (int attempt = 0; attempt < offsets.length; attempt++) {
            int[] offset = offsets[(ordinal + attempt) % offsets.length];
            int centerX = settlement.x() + offset[0];
            int centerZ = settlement.z() + offset[1];
            if (!level.hasChunkAt(new BlockPos(centerX, settlement.y(), centerZ))) continue;
            int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, centerX, centerZ);
            if (groundY <= level.getMinBuildHeight() + 1 || groundY + 6 >= level.getMaxBuildHeight()) continue;
            int radiusX = type == BuildingType.ROAD || type == BuildingType.BANK
                    || type == BuildingType.RAILWAY_STATION ? 3 : 2;
            int radiusZ = type == BuildingType.ROAD ? 0
                    : type == BuildingType.RAILWAY_STATION ? 3 : 2;
            boolean clear = true;
            for (int x = centerX - radiusX; x <= centerX + radiusX && clear; x++) {
                for (int z = centerZ - radiusZ; z <= centerZ + radiusZ; z++) {
                    BlockPos floor = new BlockPos(x, groundY, z);
                    if (!level.hasChunkAt(floor)
                            || level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) != groundY
                            || !level.getBlockState(floor.below()).isSolid()) {
                        clear = false;
                        break;
                    }
                    for (int y = groundY; y <= groundY + 5; y++) {
                        if (!mayReplaceConstructionSite(level.getBlockState(new BlockPos(x, y, z)))) {
                            clear = false;
                            break;
                        }
                    }
                    if (!clear) break;
                }
            }
            if (clear) return new BlockPos(centerX, groundY, centerZ);
        }
        return null;
    }

    public static int facilityBuildProgressPercent(Settlement settlement) {
        BuildingPlan plan = settlement.nextPhysicalBuilding();
        if (plan == null) return 100;
        if (!plan.hasSite()) return 0;
        return Math.min(99, plan.blockCursor() * 100 / Math.max(1,
                facilityPlacements(plan.type(), plan.siteX(), plan.siteY(), plan.siteZ(),
                        Palette.forStyle("generic", "")).size()));
    }

    private static java.util.List<HomePlacement> facilityPlacements(BuildingType type, int x, int y, int z,
                                                                    Palette palette) {
        java.util.List<HomePlacement> blocks = new java.util.ArrayList<>();
        switch (type) {
            case FARM -> {
                for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                    blocks.add(new HomePlacement(x + dx, y, z + dz, Blocks.FARMLAND.defaultBlockState()));
                }
                blocks.add(new HomePlacement(x, y, z, Blocks.WATER.defaultBlockState()));
                for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                    if ((dx != 0 || dz != 0) && Math.abs(dx) + Math.abs(dz) > 1) {
                        blocks.add(new HomePlacement(x + dx, y + 1, z + dz, Blocks.WHEAT.defaultBlockState()));
                    }
                }
                blocks.add(new HomePlacement(x, y + 1, z + 2, Blocks.COMPOSTER.defaultBlockState()));
            }
            case MINE -> {
                fillTemplate(blocks, x, y, z, -2, 0, -2, 2, 0, 2, palette.path());
                for (int dx : new int[]{-2, 2}) {
                    for (int dy = 1; dy <= 3; dy++) {
                        blocks.add(new HomePlacement(x + dx, y + dy, z - 2, palette.column()));
                        blocks.add(new HomePlacement(x + dx, y + dy, z + 2, palette.column()));
                    }
                }
                fillTemplate(blocks, x, y, z, -2, 4, -2, 2, 4, 2, palette.roof());
                for (int dz = -1; dz <= 1; dz++) {
                    blocks.add(new HomePlacement(x, y + 1, z + dz, palette.foundation()));
                }
            }
            case WAREHOUSE, BARRACKS, CLINIC, SCHOOL -> {
                BlockState wall = type == BuildingType.WAREHOUSE || type == BuildingType.CLINIC
                        || type == BuildingType.SCHOOL ? palette.houseWall() : palette.column();
                fillTemplate(blocks, x, y, z, -2, 0, -2, 2, 0, 2, palette.foundation());
                for (int dy = 1; dy <= 3; dy++) for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                    if (Math.abs(dx) == 2 || Math.abs(dz) == 2) {
                        if (dz == -2 && dx == 0 && dy <= 2) continue;
                        boolean window = dy == 2 && (dx == 0 || dz == 0)
                                && !(dz == -2 && dx == 0);
                        boolean corner = Math.abs(dx) == 2 && Math.abs(dz) == 2;
                        blocks.add(new HomePlacement(x + dx, y + dy, z + dz,
                                window ? Blocks.GLASS_PANE.defaultBlockState() : corner ? palette.column() : wall));
                    }
                }
                addBuildingRoofTemplate(blocks, x, y, z, 2, 2, palette);
                blocks.add(new HomePlacement(x, y + 1, z - 2, Blocks.OAK_DOOR.defaultBlockState()));
                blocks.add(new HomePlacement(x, y + 2, z - 2, Blocks.OAK_DOOR.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.DoorBlock.HALF,
                                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER)));
                if (type == BuildingType.BARRACKS) {
                    blocks.add(new HomePlacement(x, y + 1, z + 1, Blocks.HAY_BLOCK.defaultBlockState()));
                    blocks.add(new HomePlacement(x, y + 2, z + 1, Blocks.JACK_O_LANTERN.defaultBlockState()));
                } else if (type == BuildingType.CLINIC) {
                    blocks.add(new HomePlacement(x, y + 1, z, Blocks.WHITE_BED.defaultBlockState()));
                    blocks.add(new HomePlacement(x - 1, y + 1, z, Blocks.BREWING_STAND.defaultBlockState()));
                    blocks.add(new HomePlacement(x + 1, y + 1, z, Blocks.BARREL.defaultBlockState()));
                } else if (type == BuildingType.SCHOOL) {
                    blocks.add(new HomePlacement(x - 1, y + 1, z, Blocks.LECTERN.defaultBlockState()));
                    blocks.add(new HomePlacement(x + 1, y + 1, z, Blocks.BOOKSHELF.defaultBlockState()));
                    blocks.add(new HomePlacement(x, y + 1, z + 1, Blocks.BOOKSHELF.defaultBlockState()));
                }
            }
            case FORTIFICATION -> {
                fillTemplate(blocks, x, y, z, -2, 0, -2, 2, 0, 2, Blocks.COBBLESTONE.defaultBlockState());
                for (int dy = 1; dy <= 3; dy++) {
                    for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                        if (Math.abs(dx) == 2 || Math.abs(dz) == 2) {
                            blocks.add(new HomePlacement(x + dx, y + dy, z + dz,
                                    dy == 3 ? Blocks.COBBLESTONE_WALL.defaultBlockState()
                                            : Blocks.COBBLESTONE.defaultBlockState()));
                        }
                    }
                }
                blocks.add(new HomePlacement(x, y + 1, z - 2, Blocks.AIR.defaultBlockState()));
                blocks.add(new HomePlacement(x, y + 2, z - 2, Blocks.AIR.defaultBlockState()));
                blocks.add(new HomePlacement(x - 2, y + 4, z, Blocks.TORCH.defaultBlockState()));
                blocks.add(new HomePlacement(x + 2, y + 4, z, Blocks.TORCH.defaultBlockState()));
            }
            case MARKET -> {
                fillTemplate(blocks, x, y, z, -2, 0, -2, 2, 0, 2, palette.path());
                for (int dx : new int[]{-2, 2}) for (int dz : new int[]{-2, 2}) {
                    blocks.add(new HomePlacement(x + dx, y + 1, z + dz, palette.column()));
                    blocks.add(new HomePlacement(x + dx, y + 3, z + dz, palette.accent()));
                }
                fillTemplate(blocks, x, y, z, -2, 2, -2, 2, 2, 2, palette.roof());
                blocks.add(new HomePlacement(x - 1, y + 1, z, Blocks.BARREL.defaultBlockState()));
                blocks.add(new HomePlacement(x + 1, y + 1, z, Blocks.BARREL.defaultBlockState()));
            }
            case BANK -> {
                fillTemplate(blocks, x, y, z, -3, 0, -2, 3, 0, 2, palette.foundation());
                for (int dy = 1; dy <= 3; dy++) {
                    for (int dx = -3; dx <= 3; dx++) for (int dz = -2; dz <= 2; dz++) {
                        if (Math.abs(dx) != 3 && Math.abs(dz) != 2) continue;
                        if (dz == -2 && dx == 0 && dy <= 2) continue;
                        boolean window = dy == 2 && (dx == 0 || dz == 0) && dz != -2;
                        boolean corner = Math.abs(dx) == 3 && Math.abs(dz) == 2;
                        blocks.add(new HomePlacement(x + dx, y + dy, z + dz,
                                window ? Blocks.GLASS_PANE.defaultBlockState()
                                        : corner ? palette.column() : palette.wall()));
                    }
                }
                addBuildingRoofTemplate(blocks, x, y, z, 3, 2, palette);
                blocks.add(new HomePlacement(x, y + 1, z - 2, Blocks.OAK_DOOR.defaultBlockState()));
                blocks.add(new HomePlacement(x, y + 2, z - 2, Blocks.OAK_DOOR.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.DoorBlock.HALF,
                                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER)));
                blocks.add(new HomePlacement(x, y + 1, z, Blocks.LECTERN.defaultBlockState()));
                blocks.add(new HomePlacement(x - 1, y + 1, z + 1, Blocks.BARREL.defaultBlockState()));
                blocks.add(new HomePlacement(x + 1, y + 1, z + 1, Blocks.BARREL.defaultBlockState()));
                blocks.add(new HomePlacement(x, y + 1, z - 2, palette.accent()));
            }
            case RAILWAY_STATION -> {
                fillTemplate(blocks, x, y, z, -3, 0, -2, 3, 0, 2, palette.foundation());
                for (int dy = 1; dy <= 3; dy++) {
                    for (int dx = -3; dx <= 3; dx++) for (int dz = -2; dz <= 2; dz++) {
                        if (Math.abs(dx) != 3 && Math.abs(dz) != 2) continue;
                        if (dz == -2 && dx == 0 && dy <= 2) continue;
                        blocks.add(new HomePlacement(x + dx, y + dy, z + dz,
                                Math.abs(dx) == 3 && Math.abs(dz) == 2 ? palette.column() : palette.houseWall()));
                    }
                }
                addBuildingRoofTemplate(blocks, x, y, z, 3, 2, palette);
                blocks.add(new HomePlacement(x, y + 1, z - 2, Blocks.OAK_DOOR.defaultBlockState()));
                blocks.add(new HomePlacement(x, y + 2, z - 2, Blocks.OAK_DOOR.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.DoorBlock.HALF,
                                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER)));
                // Short station platform and a visible rail spur, all inside the checked footprint.
                for (int dx = -2; dx <= 2; dx++) {
                    blocks.add(new HomePlacement(x + dx, y, z + 3, palette.path()));
                    blocks.add(new HomePlacement(x + dx, y + 1, z + 3, Blocks.RAIL.defaultBlockState()));
                }
                blocks.add(new HomePlacement(x - 2, y + 1, z, Blocks.BARREL.defaultBlockState()));
                blocks.add(new HomePlacement(x + 2, y + 1, z, Blocks.BELL.defaultBlockState()));
            }
            case ROAD -> {
                for (int dx = -3; dx <= 3; dx++) {
                    blocks.add(new HomePlacement(x + dx, y, z, palette.path()));
                }
            }
        }
        return blocks;
    }

    private static void fillTemplate(java.util.List<HomePlacement> blocks, int x, int y, int z,
                                     int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                                     BlockState state) {
        for (int dy = minY; dy <= maxY; dy++) for (int dx = minX; dx <= maxX; dx++)
            for (int dz = minZ; dz <= maxZ; dz++) {
                blocks.add(new HomePlacement(x + dx, y + dy, z + dz, state));
            }
    }

    private static void columnTemplate(java.util.List<HomePlacement> blocks, int x, int y, int z,
                                       int dx, int dz, int minY, int maxY, BlockState state) {
        for (int dy = minY; dy <= maxY; dy++) {
            blocks.add(new HomePlacement(x + dx, y + dy, z + dz, state));
        }
    }

    private static void addBuildingRoofTemplate(java.util.List<HomePlacement> blocks, int x, int y, int z,
                                                int radiusX, int radiusZ, Palette palette) {
        if (palette.roofForm() == 0) {
            fillTemplate(blocks, x, y, z, -radiusX, 4, -radiusZ, radiusX, 4, radiusZ, palette.roof());
            for (int dx = -radiusX; dx <= radiusX; dx++) for (int dz = -radiusZ; dz <= radiusZ; dz++) {
                if (Math.abs(dx) == radiusX || Math.abs(dz) == radiusZ) {
                    blocks.add(new HomePlacement(x + dx, y + 5, z + dz, palette.column()));
                }
            }
            return;
        }
        for (int dz = -radiusZ; dz <= radiusZ; dz++) {
            blocks.add(new HomePlacement(x - radiusX, y + 4, z + dz, palette.roof()));
            blocks.add(new HomePlacement(x + radiusX, y + 4, z + dz, palette.roof()));
        }
        for (int dx = -radiusX + 1; dx < radiusX; dx++) {
            blocks.add(new HomePlacement(x + dx, y + 5, z, palette.accent()));
        }
        if (palette.roofForm() == 2) {
            for (int dx : new int[]{-radiusX, radiusX}) for (int dz : new int[]{-radiusZ, radiusZ}) {
                blocks.add(new HomePlacement(x + dx, y + 5, z + dz, palette.accent()));
            }
        } else if (palette.roofForm() == 3) {
            blocks.add(new HomePlacement(x, y + 5, z - 1, palette.roof()));
            blocks.add(new HomePlacement(x, y + 5, z + 1, palette.roof()));
        }
    }

    private enum HomeVariant { STARTER, FAMILY, FARMER, CRAFTSMAN, DEVELOPED }

    private static HomeVariant selectHomeVariant(Settlement city, Citizen.Profession profession,
                                                  String era, int index) {
        String historicalEra = era == null ? "" : era.toLowerCase(java.util.Locale.ROOT);
        boolean developed = city.population() >= 24 || historicalEra.contains("medieval")
                || historicalEra.contains("classical") || historicalEra.contains("industrial");
        if (developed && index >= 6) return HomeVariant.DEVELOPED;
        if (profession == Citizen.Profession.FARMER || profession == Citizen.Profession.SHEPHERD)
            return HomeVariant.FARMER;
        if (profession == Citizen.Profession.BLACKSMITH || profession == Citizen.Profession.ENGINEER
                || profession == Citizen.Profession.BUILDER) return HomeVariant.CRAFTSMAN;
        if (index < 4) return HomeVariant.STARTER;
        return HomeVariant.FAMILY;
    }

    private static java.util.List<HomePlacement> homePlacements(int x, int y, int z, Palette palette,
                                                                 HomeVariant variant) {
        java.util.List<HomePlacement> result = new java.util.ArrayList<>(48);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                result.add(new HomePlacement(x + dx, y, z + dz, palette.foundation()));
            }
        }
        for (int wallY = y + 1; wallY <= y + 2; wallY++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if ((Math.abs(dx) == 1 || Math.abs(dz) == 1)
                            && !(dz == -1 && dx == 0 && wallY == y + 1)) {
                        boolean corner = Math.abs(dx) == 1 && Math.abs(dz) == 1;
                        boolean window = wallY == y + 2 && (dx == 0 || dz == 0)
                                && !(dz == -1 && dx == 0);
                        BlockState wall;
                        if (variant == HomeVariant.STARTER) {
                            wall = corner ? palette.column() : Blocks.PACKED_MUD.defaultBlockState();
                        } else if (variant == HomeVariant.CRAFTSMAN && wallY == y + 1) {
                            wall = corner ? palette.column() : palette.wall();
                        } else {
                            wall = window ? Blocks.GLASS_PANE.defaultBlockState()
                                    : corner ? palette.column() : palette.houseWall();
                        }
                        result.add(new HomePlacement(x + dx, wallY, z + dz, wall));
                    }
                }
            }
        }
        if (variant == HomeVariant.STARTER || variant == HomeVariant.FARMER) {
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                result.add(new HomePlacement(x + dx, y + 3, z + dz,
                        variant == HomeVariant.FARMER ? Blocks.HAY_BLOCK.defaultBlockState() : palette.roof()));
            }
            if (variant == HomeVariant.FARMER) {
                result.add(new HomePlacement(x + 1, y + 1, z + 2, Blocks.COMPOSTER.defaultBlockState()));
                result.add(new HomePlacement(x - 1, y + 1, z + 2, Blocks.HAY_BLOCK.defaultBlockState()));
            }
        } else if (variant == HomeVariant.CRAFTSMAN) {
            // A small workbench and a cobblestone flue are built from stock the residents can gather.
            result.add(new HomePlacement(x - 1, y + 1, z + 2, Blocks.CRAFTING_TABLE.defaultBlockState()));
            for (int chimneyY = 1; chimneyY <= 4; chimneyY++)
                result.add(new HomePlacement(x + 1, y + chimneyY, z + 1, Blocks.COBBLESTONE.defaultBlockState()));
            addGableRoof(result, x, y, z, palette);
        } else if (variant == HomeVariant.DEVELOPED) {
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (Math.abs(dx) == 1 || Math.abs(dz) == 1) {
                    result.add(new HomePlacement(x + dx, y + 3, z + dz, palette.houseWall()));
                }
            }
            for (int dz = -1; dz <= 1; dz++) {
                result.add(new HomePlacement(x - 1, y + 4, z + dz, palette.roof()));
                result.add(new HomePlacement(x + 1, y + 4, z + dz, palette.roof()));
                result.add(new HomePlacement(x, y + 5, z + dz, palette.accent()));
            }
        } else if (palette.roofForm() == 0) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    result.add(new HomePlacement(x + dx, y + 3, z + dz, palette.roof()));
                }
            }
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (Math.abs(dx) == 1 || Math.abs(dz) == 1) {
                    result.add(new HomePlacement(x + dx, y + 4, z + dz, palette.column()));
                }
            }
        } else {
            // Compact village-house gable: low eaves with a raised wooden ridge.
            for (int dz = -1; dz <= 1; dz++) {
                result.add(new HomePlacement(x - 1, y + 3, z + dz, palette.roof()));
                result.add(new HomePlacement(x + 1, y + 3, z + dz, palette.roof()));
                result.add(new HomePlacement(x, y + 4, z + dz, palette.accent()));
            }
            if (palette.roofForm() == 2) {
                for (int dx : new int[]{-1, 1}) for (int dz : new int[]{-1, 1}) {
                    result.add(new HomePlacement(x + dx, y + 4, z + dz, palette.accent()));
                }
            } else if (palette.roofForm() == 3) {
                result.add(new HomePlacement(x, y + 5, z, palette.column()));
                result.add(new HomePlacement(x, y + 5, z - 1, palette.roof()));
                result.add(new HomePlacement(x, y + 5, z + 1, palette.roof()));
            }
        }
        result.add(new HomePlacement(x, y + 1, z, Blocks.RED_BED.defaultBlockState()));
        result.add(new HomePlacement(x, y + 1, z - 1, Blocks.OAK_DOOR.defaultBlockState()));
        result.add(new HomePlacement(x, y + 2, z - 1, Blocks.OAK_DOOR.defaultBlockState()
                .setValue(net.minecraft.world.level.block.DoorBlock.HALF,
                        net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER)));
        return result;
    }

    private static void addGableRoof(java.util.List<HomePlacement> result, int x, int y, int z, Palette palette) {
        for (int dz = -1; dz <= 1; dz++) {
            result.add(new HomePlacement(x - 1, y + 3, z + dz, palette.roof()));
            result.add(new HomePlacement(x + 1, y + 3, z + dz, palette.roof()));
            result.add(new HomePlacement(x, y + 4, z + dz, palette.accent()));
        }
    }

    private static boolean isValidHomeSite(ServerLevel level, int x, int y, int z) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int worldX = x + dx;
                int worldZ = z + dz;
                if (!level.hasChunkAt(new BlockPos(worldX, y, worldZ))
                        || level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, worldX, worldZ) != y
                        || !level.getBlockState(new BlockPos(worldX, y - 1, worldZ)).isSolid()) return false;
                for (int dy = 1; dy <= 5; dy++) {
                    BlockPos position = new BlockPos(worldX, y + dy, worldZ);
                    if (!level.hasChunkAt(position) || !mayReplaceConstructionSite(level.getBlockState(position)))
                        return false;
                }
            }
        }
        return true;
    }

    private record HomePlacement(int x, int y, int z, BlockState state) { }

    private static boolean mayReplaceConstructionSite(BlockState state) {
        return state.isAir() || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.PACKED_MUD)
                || state.is(Blocks.HAY_BLOCK) || state.is(Blocks.RED_BED) || state.is(Blocks.TORCH)
                || state.is(Blocks.DIRT) || state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.SHORT_GRASS)
                || state.is(Blocks.TALL_GRASS) || state.is(Blocks.FERN) || state.is(Blocks.DEAD_BUSH)
                || state.is(BlockTags.LEAVES) || state.is(BlockTags.SAPLINGS) || state.is(BlockTags.FLOWERS);
    }

    private static void clearHomeSite(ServerLevel level, ChunkPos chunk, int x, int y, int z) {
        for (int clearY = y + 1; clearY <= y + 4; clearY++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    set(level, chunk, x + dx, clearY, z + dz, Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    private static void buildEarthHut(ServerLevel level, ChunkPos chunk, int x, int y, int z,
                                      Palette palette, String style) {
        fill(level, chunk, x - 1, y, z - 1, x + 1, y, z + 1, palette.foundation());
        for (int wallY = y + 1; wallY <= y + 2; wallY++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if ((Math.abs(dx) == 1 || Math.abs(dz) == 1)
                            && !(dz == -1 && dx == 0)) {
                        boolean corner = Math.abs(dx) == 1 && Math.abs(dz) == 1;
                        set(level, chunk, x + dx, wallY, z + dz, corner ? palette.column()
                                : Blocks.PACKED_MUD.defaultBlockState());
                    }
                }
            }
        }
        for (int dz = -1; dz <= 1; dz++) {
            set(level, chunk, x - 1, y + 3, z + dz, Blocks.HAY_BLOCK.defaultBlockState());
            set(level, chunk, x + 1, y + 3, z + dz, Blocks.HAY_BLOCK.defaultBlockState());
            set(level, chunk, x, y + 4, z + dz, palette.roof());
        }
        set(level, chunk, x, y + 1, z, Blocks.RED_BED.defaultBlockState());
        set(level, chunk, x, y + 1, z + 1, Blocks.TORCH.defaultBlockState());
        if ("mayan".equals(style) || "andean".equals(style)) {
            set(level, chunk, x, y + 2, z, palette.accent());
        }
    }

    private static void prepareGround(ServerLevel level, ChunkPos chunk, int centerX, int floorY,
                                      int centerZ, BlockState support) {
        int targetGroundY = floorY - 1;
        for (int x = centerX - 7; x <= centerX + 7; x++) {
            for (int z = centerZ - 7; z <= centerZ + 7; z++) {
                BlockPos probe = new BlockPos(x, floorY, z);
                if (!chunk.equals(new ChunkPos(probe))) {
                    continue;
                }
                int topY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                while (topY > level.getMinBuildHeight()
                        && !level.getFluidState(new BlockPos(x, topY, z)).isEmpty()) {
                    set(level, chunk, x, topY--, z, Blocks.AIR.defaultBlockState());
                }
                if (topY > targetGroundY) {
                    for (int y = targetGroundY + 1; y <= topY; y++) {
                        set(level, chunk, x, y, z, Blocks.AIR.defaultBlockState());
                    }
                } else if (topY < targetGroundY) {
                    for (int y = topY + 1; y <= targetGroundY; y++) {
                        set(level, chunk, x, y, z, support);
                    }
                }
            }
        }
    }

    private static void buildTownHall(ServerLevel level, ChunkPos chunk, int x, int y, int z,
                                      Palette palette, String style) {
        fill(level, chunk, x - 3, y, z - 3, x + 3, y, z + 3, palette.foundation());
        for (int wallY = y + 1; wallY <= y + 3; wallY++) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    if (Math.abs(dx) == 3 || Math.abs(dz) == 3) {
                        boolean doorway = dz == -3 && Math.abs(dx) <= 1 && wallY <= y + 2;
                        boolean window = wallY == y + 2 && Math.abs(dx) == 3 && dz == 0;
                        if (!doorway) {
                            set(level, chunk, x + dx, wallY, z + dz,
                                    window ? Blocks.GLASS_PANE.defaultBlockState() : palette.wall());
                        }
                    }
                }
            }
        }
        for (int dx : new int[]{-2, 0, 2}) {
            column(level, chunk, x + dx, y + 1, z - 3, y + 4, palette.column());
        }
        fill(level, chunk, x - 3, y + 4, z - 3, x + 3, y + 4, z + 3, palette.roof());
        fill(level, chunk, x - 2, y + 5, z - 2, x + 2, y + 5, z + 2, palette.accent());
        if ("mayan".equals(style) || "andean".equals(style)) {
            fill(level, chunk, x - 2, y + 6, z - 1, x + 2, y + 6, z + 1, palette.roof());
        } else if ("egyptian".equals(style) || "mesopotamian".equals(style)) {
            column(level, chunk, x, y + 6, z, y + 8, palette.column());
            set(level, chunk, x, y + 9, z, palette.roof());
        } else {
            for (int dx : new int[]{-3, 3}) {
                column(level, chunk, x + dx, y + 5, z + 2, y + 7, palette.accent());
            }
        }
        set(level, chunk, x, y + 1, z - 2, Blocks.LANTERN.defaultBlockState());
        set(level, chunk, x, y + 1, z, Blocks.BELL.defaultBlockState());
    }

    private static void ensureTownHall(ServerLevel level, Settlement settlement, Palette palette, String style) {
        BlockPos bell = new BlockPos(settlement.x(), settlement.y() + 1, settlement.z());
        if (!level.hasChunkAt(bell)) return;
        ChunkPos chunk = new ChunkPos(settlement.x() >> 4, settlement.z() >> 4);
        if (!level.getBlockState(bell).is(Blocks.BELL)) {
            buildTownHall(level, chunk, settlement.x(), settlement.y(), settlement.z(), palette, style);
        }
        dev.autociv.simulation.world.TownHallStorage.ensureChestAtTownHall(level, settlement);
    }

    private static void buildHouse(ServerLevel level, ChunkPos chunk, int x, int y, int z, Palette palette) {
        fill(level, chunk, x - 1, y, z - 1, x + 1, y, z + 1, palette.foundation());
        for (int wallY = y + 1; wallY <= y + 2; wallY++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (Math.abs(dx) == 1 || Math.abs(dz) == 1) {
                        boolean door = dz == -1 && dx == 0 && wallY == y + 1;
                        if (!door) {
                            set(level, chunk, x + dx, wallY, z + dz, palette.houseWall());
                        }
                    }
                }
            }
        }
        fill(level, chunk, x - 1, y + 3, z - 1, x + 1, y + 3, z + 1, palette.roof());
        set(level, chunk, x, y + 1, z, Blocks.RED_BED.defaultBlockState());
    }

    private static void buildFarm(ServerLevel level, ChunkPos chunk, int x, int y, int z, BlockState path) {
        // Keep irrigation inside a low solid curb so it cannot run out over the village plateau.
        for (int borderX = x - 3; borderX <= x + 3; borderX++) {
            set(level, chunk, borderX, y, z - 1, path);
            set(level, chunk, borderX, y, z + 2, path);
        }
        for (int borderZ = z - 1; borderZ <= z + 2; borderZ++) {
            set(level, chunk, x - 3, y, borderZ, path);
            set(level, chunk, x + 3, y, borderZ, path);
        }
        fill(level, chunk, x - 2, y, z, x + 2, y, z + 1, Blocks.FARMLAND.defaultBlockState());
        fill(level, chunk, x, y, z, x, y, z + 1, Blocks.WATER.defaultBlockState());
        fill(level, chunk, x - 2, y + 1, z, x - 1, y + 1, z + 1, Blocks.WHEAT.defaultBlockState());
        fill(level, chunk, x + 1, y + 1, z, x + 2, y + 1, z + 1, Blocks.WHEAT.defaultBlockState());
        set(level, chunk, x + 3, y + 1, z, Blocks.COMPOSTER.defaultBlockState());
        set(level, chunk, x + 3, y, z, path);
    }

    private static void buildMine(ServerLevel level, ChunkPos chunk, int x, int y, int z, Palette palette) {
        BlockState path = palette.path();
        fill(level, chunk, x, y, z, x + 3, y, z + 4, path);
        for (int supportX : new int[]{x, x + 3}) {
            column(level, chunk, supportX, y + 1, z, y + 3, palette.column());
        }
        fill(level, chunk, x, y + 3, z, x + 3, y + 3, z, palette.roof());
        for (int pileZ = z + 1; pileZ <= z + 3; pileZ++) {
            set(level, chunk, x + 2, y + 1, pileZ, Blocks.COBBLESTONE.defaultBlockState());
        }
        set(level, chunk, x + 1, y + 1, z, Blocks.TORCH.defaultBlockState());
    }

    private static void buildWoodYard(ServerLevel level, ChunkPos chunk, int x, int y, int z, Palette palette) {
        BlockState path = palette.path();
        fill(level, chunk, x, y, z, x + 3, y, z + 4, path);
        set(level, chunk, x + 1, y + 1, z + 1, Blocks.FLETCHING_TABLE.defaultBlockState());
        set(level, chunk, x + 2, y + 1, z + 1, Blocks.CAMPFIRE.defaultBlockState());
        // The lumberjack's regrowing tree stands on soil at x+2,z+3.
        set(level, chunk, x + 2, y, z + 3, Blocks.DIRT.defaultBlockState());
        for (int trunkY = y + 1; trunkY <= y + 4; trunkY++) {
            set(level, chunk, x + 2, trunkY, z + 3, palette.column());
        }
        for (int leafX = x + 1; leafX <= x + 3; leafX++) {
            for (int leafZ = z + 2; leafZ <= z + 4; leafZ++) {
                set(level, chunk, leafX, y + 5, leafZ, palette.leaves());
            }
        }
    }

    private static void column(ServerLevel level, ChunkPos chunk, int x, int y0, int z,
                               int y1, BlockState state) {
        for (int y = y0; y <= y1; y++) {
            set(level, chunk, x, y, z, state);
        }
    }

    private static void fill(ServerLevel level, ChunkPos chunk, int x0, int y0, int z0,
                             int x1, int y1, int z1, BlockState state) {
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    set(level, chunk, x, y, z, state);
                }
            }
        }
    }

    private static void set(ServerLevel level, ChunkPos chunk, int x, int y, int z, BlockState state) {
        BlockPos position = new BlockPos(x, y, z);
        if (!level.isInWorldBounds(position) || !chunk.equals(new ChunkPos(position))) {
            return;
        }
        if (state.hasBlockEntity()) {
            level.getChunkAt(position);
        }
        level.setBlock(position, state, 2);
    }

    private static Palette palette(ServerLevel level, int x, int z, String style) {
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        String biome = level.getBiome(new BlockPos(x, surfaceY, z)).unwrapKey()
                .map(key -> key.location().getPath()).orElse("");
        return Palette.forStyle(style, biome);
    }

    private record Palette(BlockState foundation, BlockState wall, BlockState houseWall,
                           BlockState roof, BlockState accent, BlockState column, BlockState path,
                           BlockState leaves,
                           int roofForm) {
        private static Palette forStyle(String style, String biome) {
            BlockState log;
            BlockState planks;
            BlockState leaves;
            if (biome.contains("mangrove")) {
                log = Blocks.MANGROVE_LOG.defaultBlockState(); planks = Blocks.MANGROVE_PLANKS.defaultBlockState();
                leaves = Blocks.MANGROVE_LEAVES.defaultBlockState();
            } else if (biome.contains("jungle") || biome.contains("bamboo")) {
                log = Blocks.JUNGLE_LOG.defaultBlockState(); planks = Blocks.JUNGLE_PLANKS.defaultBlockState();
                leaves = Blocks.JUNGLE_LEAVES.defaultBlockState();
            } else if (biome.contains("savanna")) {
                log = Blocks.ACACIA_LOG.defaultBlockState(); planks = Blocks.ACACIA_PLANKS.defaultBlockState();
                leaves = Blocks.ACACIA_LEAVES.defaultBlockState();
            } else if (biome.contains("taiga") || biome.contains("grove") || biome.contains("snowy")) {
                log = Blocks.SPRUCE_LOG.defaultBlockState(); planks = Blocks.SPRUCE_PLANKS.defaultBlockState();
                leaves = Blocks.SPRUCE_LEAVES.defaultBlockState();
            } else if (biome.contains("cherry")) {
                log = Blocks.CHERRY_LOG.defaultBlockState(); planks = Blocks.CHERRY_PLANKS.defaultBlockState();
                leaves = Blocks.CHERRY_LEAVES.defaultBlockState();
            } else if (biome.contains("birch")) {
                log = Blocks.BIRCH_LOG.defaultBlockState(); planks = Blocks.BIRCH_PLANKS.defaultBlockState();
                leaves = Blocks.BIRCH_LEAVES.defaultBlockState();
            } else if (biome.contains("dark_forest")) {
                log = Blocks.DARK_OAK_LOG.defaultBlockState(); planks = Blocks.DARK_OAK_PLANKS.defaultBlockState();
                leaves = Blocks.DARK_OAK_LEAVES.defaultBlockState();
            } else {
                log = Blocks.OAK_LOG.defaultBlockState(); planks = Blocks.OAK_PLANKS.defaultBlockState();
                leaves = Blocks.OAK_LEAVES.defaultBlockState();
            }

            // Use a block miners can deliver directly; stone bricks require a furnace step.
            BlockState stone = Blocks.COBBLESTONE.defaultBlockState();
            BlockState soil = style.equals("mesopotamian")
                    ? Blocks.COARSE_DIRT.defaultBlockState() : Blocks.DIRT.defaultBlockState();
            // Civilization profiles alter the silhouette and timber details; every palette
            // stays within common logs, planks, dirt, and mined stone.
            BlockState wall = switch (style) {
                case "mayan", "andean", "hellenic" -> stone;
                case "mesopotamian", "egyptian" -> Blocks.COARSE_DIRT.defaultBlockState();
                default -> planks;
            };
            BlockState path = style.equals("mesopotamian") || style.equals("egyptian")
                    ? Blocks.COARSE_DIRT.defaultBlockState() : stone;
            int roofForm = style.equals("egyptian") || style.equals("mesopotamian")
                    || biome.contains("desert") || biome.contains("badlands") ? 0
                    : style.equals("chinese") || style.equals("indian")
                    || style.equals("southeast_asian") ? 2
                    : style.equals("mayan") || style.equals("andean") ? 3 : 1;
            return new Palette(soil, wall, planks, planks, log, log, path, leaves, roofForm);
        }
    }
}
