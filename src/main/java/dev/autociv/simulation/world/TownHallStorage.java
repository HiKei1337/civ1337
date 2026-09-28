package dev.autociv.simulation.world;

import dev.autociv.simulation.model.ResourceType;
import dev.autociv.simulation.model.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.tags.BlockTags;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Physical shared warehouse in a settlement town hall. */
public final class TownHallStorage {

    public enum Kind { FOOD, WOOD, MINING, GENERAL }

    public record StorageEntry(BlockPos position, Container container, Kind kind) { }

    private record PairOffset(int x, int z, Kind kind) { }

    // Six real double chests arranged in three rows inside the town hall; each pair is a category lane.
    private static final List<PairOffset> PAIRS = List.of(
            new PairOffset(-2, -2, Kind.FOOD), new PairOffset(1, -2, Kind.WOOD),
            new PairOffset(-2, 0, Kind.MINING), new PairOffset(1, 0, Kind.GENERAL),
            new PairOffset(-2, 2, Kind.FOOD), new PairOffset(1, 2, Kind.GENERAL));

    private TownHallStorage() { }

    public static BlockPos position(Settlement settlement) {
        return new BlockPos(settlement.x(), settlement.y() + 1, settlement.z() + 1);
    }

    public static Container container(ServerLevel level, Settlement settlement) {
        List<StorageEntry> entries = entries(level, settlement, position(settlement));
        return entries.isEmpty() ? null : entries.get(0).container();
    }

    /** All currently loaded halves of the town's registered chest pairs, nearest first. */
    public static List<StorageEntry> entries(ServerLevel level, Settlement settlement, BlockPos from) {
        if (!settlement.physicalSettlementGenerated()) return List.of();
        List<StorageEntry> result = new ArrayList<>(13);
        BlockPos legacy = position(settlement);
        addEntry(level, result, legacy, Kind.GENERAL);
        for (PairOffset pair : PAIRS) {
            addEntry(level, result, new BlockPos(settlement.x() + pair.x(), settlement.y() + 1,
                    settlement.z() + pair.z()), pair.kind());
            addEntry(level, result, new BlockPos(settlement.x() + pair.x() + 1, settlement.y() + 1,
                    settlement.z() + pair.z()), pair.kind());
        }
        result.sort(Comparator.comparingDouble(entry -> entry.position().distSqr(from)));
        return List.copyOf(result);
    }

    public static List<Container> containers(ServerLevel level, Settlement settlement) {
        return entries(level, settlement, position(settlement)).stream().map(StorageEntry::container).toList();
    }

    public static List<Container> containers(ServerLevel level, Settlement settlement, BlockPos from) {
        return entries(level, settlement, from).stream().map(StorageEntry::container).toList();
    }

    public static List<Container> containersFor(ServerLevel level, Settlement settlement, BlockPos from,
                                               ResourceType resource) {
        Kind preferred = kindFor(resource);
        return entries(level, settlement, from).stream()
                .sorted(Comparator.comparingInt((StorageEntry entry) -> entry.kind() == preferred ? 0
                                : entry.kind() == Kind.GENERAL ? 1 : 2)
                        .thenComparingDouble(entry -> entry.position().distSqr(from)))
                .map(StorageEntry::container).toList();
    }

    private static Kind kindFor(ResourceType resource) {
        if (resource.equals(ResourceType.FOOD)) return Kind.FOOD;
        if (resource.equals(ResourceType.WOOD)) return Kind.WOOD;
        if (resource.equals(ResourceType.STONE) || resource.equals(ResourceType.COAL)
                || resource.equals(ResourceType.IRON) || resource.equals(ResourceType.COPPER)) return Kind.MINING;
        return Kind.GENERAL;
    }

    public static BlockPos nearestStoragePosition(ServerLevel level, Settlement settlement, BlockPos from) {
        return entries(level, settlement, from).stream().map(StorageEntry::position).findFirst()
                .orElse(position(settlement));
    }

    public static boolean isStoragePosition(Settlement settlement, BlockPos position) {
        if (TownHallStorage.position(settlement).equals(position)) return true;
        for (PairOffset pair : PAIRS) {
            int x = settlement.x() + pair.x();
            int z = settlement.z() + pair.z();
            if (position.getY() == settlement.y() + 1 && position.getZ() == z
                    && (position.getX() == x || position.getX() == x + 1)) return true;
        }
        return false;
    }

    public static BlockPos closestStoragePosition(Settlement settlement, BlockPos from) {
        List<BlockPos> positions = new ArrayList<>(13);
        positions.add(position(settlement));
        for (PairOffset pair : PAIRS) {
            BlockPos first = new BlockPos(settlement.x() + pair.x(), settlement.y() + 1,
                    settlement.z() + pair.z());
            positions.add(first);
            positions.add(first.east());
        }
        return positions.stream().min(Comparator.comparingDouble(from::distSqr)).orElse(position(settlement));
    }

    private static void addEntry(ServerLevel level, List<StorageEntry> entries, BlockPos pos, Kind kind) {
        if (level.hasChunkAt(pos) && level.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
            entries.add(new StorageEntry(pos, chest, kind));
        }
    }

    public static boolean ensureChestAtTownHall(ServerLevel level, Settlement settlement) {
        BlockPos bell = new BlockPos(settlement.x(), settlement.y() + 1, settlement.z());
        BlockPos chest = position(settlement);
        if (!level.hasChunkAt(bell) || !level.hasChunkAt(chest)
                || !level.getBlockState(bell).is(Blocks.BELL)) return false;
        if (!placeChest(level, chest)) return false;
        for (PairOffset pair : PAIRS) {
            BlockPos first = new BlockPos(settlement.x() + pair.x(), settlement.y() + 1,
                    settlement.z() + pair.z());
            BlockPos second = first.east();
            // Never replace player blocks: a blocked lane is skipped, other lanes remain usable.
            if (canPlaceChest(level, first) && canPlaceChest(level, second)) {
                placeChest(level, first);
                placeChest(level, second);
            }
        }
        return true;
    }

    private static boolean canPlaceChest(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) return false;
        var state = level.getBlockState(pos);
        return state.is(Blocks.CHEST) || state.isAir() || state.is(Blocks.SHORT_GRASS)
                || state.is(Blocks.TALL_GRASS) || state.is(Blocks.TORCH);
    }

    private static boolean placeChest(ServerLevel level, BlockPos pos) {
        if (!canPlaceChest(level, pos)) return false;
        if (!level.getBlockState(pos).is(Blocks.CHEST)) {
            return level.setBlock(pos, Blocks.CHEST.defaultBlockState(), 3);
        }
        return true;
    }

    public static Item itemFor(ResourceType resource) {
        if (resource.equals(ResourceType.FOOD)) return Items.WHEAT;
        if (resource.equals(ResourceType.WOOD)) return Items.OAK_LOG;
        if (resource.equals(ResourceType.STONE)) return Items.COBBLESTONE;
        if (resource.equals(ResourceType.COAL)) return Items.COAL;
        if (resource.equals(ResourceType.IRON)) return Items.IRON_INGOT;
        if (resource.equals(ResourceType.COPPER)) return Items.COPPER_INGOT;
        if (resource.equals(ResourceType.GOLD)) return Items.GOLD_INGOT;
        if (resource.equals(ResourceType.TOOLS)) return Items.IRON_PICKAXE;
        if (resource.equals(ResourceType.WEAPONS)) return Items.IRON_SWORD;
        if (resource.equals(ResourceType.ARMOR)) return Items.IRON_CHESTPLATE;
        if (resource.equals(ResourceType.BUILDING_MATERIALS)) return Items.BRICKS;
        if (resource.equals(ResourceType.LUXURY_GOODS)) return Items.EMERALD;
        return Items.GOLD_NUGGET;
    }

    public static ResourceType resourceFor(Item item) {
        if (item instanceof BlockItem blockItem) {
            var block = blockItem.getBlock().defaultBlockState();
            if (block.is(BlockTags.LOGS) || block.is(BlockTags.PLANKS)) return ResourceType.WOOD;
            if (block.is(BlockTags.BASE_STONE_OVERWORLD) || block.is(Blocks.COBBLESTONE)
                    || block.is(Blocks.COBBLED_DEEPSLATE) || block.is(Blocks.STONE_BRICKS)) {
                return ResourceType.STONE;
            }
        }
        if (item == Items.WHEAT || item == Items.BREAD || item == Items.CARROT || item == Items.POTATO
                || item == Items.BEETROOT || item == Items.APPLE || item == Items.BEETROOT_SOUP
                || item == Items.BAKED_POTATO || item == Items.COOKED_BEEF || item == Items.COOKED_PORKCHOP
                || item == Items.COOKED_CHICKEN || item == Items.COOKED_MUTTON || item == Items.COOKED_RABBIT
                || item == Items.COOKED_COD || item == Items.COOKED_SALMON) return ResourceType.FOOD;
        if (item == Items.COAL || item == Items.CHARCOAL) return ResourceType.COAL;
        if (item == Items.IRON_INGOT || item == Items.RAW_IRON) return ResourceType.IRON;
        if (item == Items.COPPER_INGOT || item == Items.RAW_COPPER) return ResourceType.COPPER;
        if (item == Items.GOLD_INGOT || item == Items.RAW_GOLD) return ResourceType.GOLD;
        if (item == Items.COBBLESTONE || item == Items.STONE || item == Items.COBBLED_DEEPSLATE
                || item == Items.DEEPSLATE) return ResourceType.STONE;
        if (item == Items.OAK_LOG || item == Items.SPRUCE_LOG || item == Items.BIRCH_LOG
                || item == Items.JUNGLE_LOG || item == Items.ACACIA_LOG || item == Items.DARK_OAK_LOG
                || item == Items.MANGROVE_LOG || item == Items.CHERRY_LOG || item == Items.OAK_PLANKS
                || item == Items.SPRUCE_PLANKS || item == Items.BIRCH_PLANKS || item == Items.JUNGLE_PLANKS
                || item == Items.ACACIA_PLANKS || item == Items.DARK_OAK_PLANKS || item == Items.MANGROVE_PLANKS
                || item == Items.CHERRY_PLANKS) return ResourceType.WOOD;
        return null;
    }

    /** Number of whole abstract resource units that can still fit in this chest. */
    public static int freeSpace(Container container, ResourceType resource) {
        Item item = itemFor(resource);
        int available = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.isEmpty()) available += item.getDefaultInstance().getMaxStackSize();
            else if (stack.is(item) && ItemStack.isSameItemSameComponents(stack, item.getDefaultInstance())) {
                available += Math.max(0, stack.getMaxStackSize() - stack.getCount());
            }
        }
        return available;
    }

    public static int freeSpace(List<Container> containers, ResourceType resource) {
        return containers.stream().mapToInt(container -> freeSpace(container, resource)).sum();
    }

    public static int store(Container container, ResourceType resource, int count) {
        int accepted = Math.min(Math.max(0, count), freeSpace(container, resource));
        if (accepted <= 0) return 0;
        Item item = itemFor(resource);
        ItemStack prototype = new ItemStack(item);
        int remaining = accepted;
        for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, prototype)) {
                int moved = Math.min(remaining, stack.getMaxStackSize() - stack.getCount());
                if (moved > 0) {
                    stack.grow(moved);
                    remaining -= moved;
                }
            }
        }
        for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
            if (!container.getItem(slot).isEmpty()) continue;
            int moved = Math.min(remaining, prototype.getMaxStackSize());
            container.setItem(slot, new ItemStack(item, moved));
            remaining -= moved;
        }
        int stored = accepted - remaining;
        if (stored > 0) container.setChanged();
        return stored;
    }

    public static int store(List<Container> containers, ResourceType resource, int count) {
        int remaining = Math.max(0, count);
        for (Container container : containers) {
            if (remaining <= 0) break;
            remaining -= store(container, resource, remaining);
        }
        return count - remaining;
    }

    public static int storeItem(Container container, ItemStack offered) {
        int count = offered.getCount();
        int remaining = count;
        for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
            ItemStack existing = container.getItem(slot);
            if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, offered)) {
                int moved = Math.min(remaining, existing.getMaxStackSize() - existing.getCount());
                existing.grow(moved);
                remaining -= moved;
            }
        }
        for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
            if (!container.getItem(slot).isEmpty()) continue;
            int moved = Math.min(remaining, offered.getMaxStackSize());
            ItemStack inserted = offered.copy();
            inserted.setCount(moved);
            container.setItem(slot, inserted);
            remaining -= moved;
        }
        int stored = count - remaining;
        if (stored > 0) container.setChanged();
        return stored;
    }

    public static int storeItem(List<Container> containers, ItemStack offered) {
        int remaining = offered.getCount();
        for (Container container : containers) {
            if (remaining <= 0) break;
            ItemStack portion = offered.copy();
            portion.setCount(remaining);
            remaining -= storeItem(container, portion);
        }
        return offered.getCount() - remaining;
    }

    public static boolean hasItem(List<Container> containers, Item item, int count) {
        return count(containers, item) >= count;
    }

    public static int countItem(List<Container> containers, Item item) {
        return count(containers, item);
    }

    /** Counts physical items classified as one resource, including biome-specific logs/planks. */
    public static int countResourceItems(List<Container> containers, ResourceType resource) {
        int total = 0;
        for (Container container : containers) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (!stack.isEmpty() && resource.equals(resourceFor(stack.getItem()))) total += stack.getCount();
            }
        }
        return total;
    }

    public static boolean consumeItem(List<Container> containers, Item item, int count) {
        if (!hasItem(containers, item, count)) return false;
        int remaining = count;
        for (Container container : containers) {
            int present = count(container, item);
            int consumed = Math.min(remaining, present);
            if (consumed > 0) remove(container, item, consumed);
            remaining -= consumed;
            if (remaining == 0) break;
        }
        containers.forEach(Container::setChanged);
        return remaining == 0;
    }

    public static boolean hasItem(Container container, Item item, int count) {
        return container != null && count(container, item) >= count;
    }

    public static boolean consumeItem(Container container, Item item, int count) {
        if (!hasItem(container, item, count)) return false;
        remove(container, item, count);
        container.setChanged();
        return true;
    }

    /** Make a small batch of torches from the warehouse's mined coal and harvested wood. */
    public static void craftTorches(Container container) {
        if (container == null || hasItem(container, Items.TORCH, 4)
                || !hasItem(container, Items.COAL, 1) || !hasItem(container, Items.OAK_LOG, 1)) return;
        if (!consumeItem(container, Items.COAL, 1) || !consumeItem(container, Items.OAK_LOG, 1)) return;
        storeItem(container, new ItemStack(Items.TORCH, 4));
    }

    public static boolean hasMaterials(Container container, int wood, int stone) {
        return container != null && count(container, itemFor(ResourceType.WOOD)) >= wood
                && count(container, itemFor(ResourceType.STONE)) >= stone;
    }

    public static boolean hasMaterials(List<Container> containers, int wood, int stone) {
        return count(containers, itemFor(ResourceType.WOOD)) >= wood
                && count(containers, itemFor(ResourceType.STONE)) >= stone;
    }

    public static boolean consumeMaterials(Container container, int wood, int stone) {
        if (!hasMaterials(container, wood, stone)) return false;
        remove(container, itemFor(ResourceType.WOOD), wood);
        remove(container, itemFor(ResourceType.STONE), stone);
        container.setChanged();
        return true;
    }

    public static boolean consumeMaterials(List<Container> containers, int wood, int stone) {
        if (!hasMaterials(containers, wood, stone)) return false;
        remove(containers, itemFor(ResourceType.WOOD), wood);
        remove(containers, itemFor(ResourceType.STONE), stone);
        containers.forEach(Container::setChanged);
        return true;
    }

    public static void craftTorches(List<Container> containers) {
        if (hasItem(containers, Items.TORCH, 4) || !hasItem(containers, Items.COAL, 1)
                || !hasItem(containers, Items.OAK_LOG, 1)) return;
        if (!consumeItem(containers, Items.COAL, 1) || !consumeItem(containers, Items.OAK_LOG, 1)) return;
        storeItem(containers, new ItemStack(Items.TORCH, 4));
    }

    /**
     * Craft one requested block using the world's datapack recipe and ingredients in the shared warehouse.
     * Only recipes with no container remainders are accepted, so crafting cannot silently lose buckets/tools.
     */
    public static boolean craftBlock(ServerLevel level, List<Container> containers, Item requestedOutput) {
        return craftBlock(level, containers, requestedOutput, java.util.Map.of());
    }

    public static boolean craftBlock(ServerLevel level, List<Container> containers, Item requestedOutput,
                                     java.util.Map<Item, Integer> reserves) {
        List<WarehouseSlot> slots = warehouseSlots(containers, reserves);
        for (RecipeHolder<CraftingRecipe> holder : level.getServer().getRecipeManager()
                .getAllRecipesFor(RecipeType.CRAFTING)) {
            CraftingRecipe recipe = holder.value();
            ItemStack result = recipe.getResultItem(level.registryAccess());
            if (result.isEmpty() || result.getItem() != requestedOutput || !(result.getItem() instanceof BlockItem)) {
                continue;
            }
            CraftingInput input = makeInput(recipe, slots, level);
            if (input == null || !recipe.matches(input, level)) continue;
            if (recipe.getRemainingItems(input).stream().anyMatch(stack -> !stack.isEmpty())) continue;
            ItemStack crafted = recipe.assemble(input, level.registryAccess());
            if (crafted.isEmpty() || !canStore(containers, crafted)) continue;
            if (!consumeInput(slots, input)) continue;
            if (storeItem(containers, crafted) != crafted.getCount()) {
                // Capacity was checked before ingredients were consumed; this is only a defensive fallback.
                return false;
            }
            return true;
        }
        return false;
    }

    private record WarehouseSlot(Container container, int index, ItemStack stack, int availableCount) { }

    private static List<WarehouseSlot> warehouseSlots(List<Container> containers, java.util.Map<Item, Integer> reserves) {
        List<WarehouseSlot> slots = new ArrayList<>();
        java.util.Map<Item, Integer> remainingReserve = new java.util.HashMap<>();
        reserves.forEach((item, count) -> remainingReserve.put(item, Math.max(0, count)));
        for (Container container : containers) {
            for (int index = 0; index < container.getContainerSize(); index++) {
                ItemStack stack = container.getItem(index);
                if (!stack.isEmpty()) {
                    int protectedCount = Math.min(stack.getCount(), remainingReserve.getOrDefault(stack.getItem(), 0));
                    remainingReserve.computeIfPresent(stack.getItem(), (item, remaining) -> remaining - protectedCount);
                    slots.add(new WarehouseSlot(container, index, stack, stack.getCount() - protectedCount));
                }
            }
        }
        return slots;
    }

    private static CraftingInput makeInput(CraftingRecipe recipe, List<WarehouseSlot> warehouse, ServerLevel level) {
        List<ItemStack> grid = new ArrayList<>(java.util.Collections.nCopies(9, ItemStack.EMPTY));
        int[] used = new int[warehouse.size()];
        if (recipe instanceof ShapedRecipe shaped) {
            int width = shaped.getWidth();
            int height = shaped.getHeight();
            List<Ingredient> ingredients = recipe.getIngredients();
            for (int offsetY = 0; offsetY <= 3 - height; offsetY++) {
                for (int offsetX = 0; offsetX <= 3 - width; offsetX++) {
                    java.util.Arrays.fill(used, 0);
                    java.util.Collections.fill(grid, ItemStack.EMPTY);
                    boolean matched = true;
                    for (int y = 0; y < height && matched; y++) {
                        for (int x = 0; x < width; x++) {
                            Ingredient ingredient = ingredients.get(y * width + x);
                            if (ingredient.isEmpty()) continue;
                            int slot = findIngredient(ingredient, warehouse, used);
                            if (slot < 0) { matched = false; break; }
                            used[slot]++;
                            grid.set((offsetY + y) * 3 + offsetX + x, warehouse.get(slot).stack().copyWithCount(1));
                        }
                    }
                    if (matched) {
                        CraftingInput input = CraftingInput.of(3, 3, grid);
                        if (recipe.matches(input, level)) return input;
                    }
                }
            }
            return null;
        }
        if (recipe instanceof ShapelessRecipe) {
            List<Ingredient> ingredients = recipe.getIngredients().stream().filter(ingredient -> !ingredient.isEmpty()).toList();
            if (!assignShapeless(0, ingredients, warehouse, used, grid, new int[]{4096})) return null;
            CraftingInput input = CraftingInput.of(3, 3, grid);
            return recipe.matches(input, level) ? input : null;
        }
        return null;
    }

    private static boolean assignShapeless(int ingredientIndex, List<Ingredient> ingredients,
                                           List<WarehouseSlot> warehouse, int[] used, List<ItemStack> grid,
                                           int[] searchBudget) {
        if (ingredientIndex == ingredients.size()) return true;
        Ingredient ingredient = ingredients.get(ingredientIndex);
        for (int gridIndex = 0; gridIndex < 9; gridIndex++) {
            if (!grid.get(gridIndex).isEmpty()) continue;
            for (int slot = 0; slot < warehouse.size(); slot++) {
                if (--searchBudget[0] < 0) return false;
                WarehouseSlot candidate = warehouse.get(slot);
                if (used[slot] >= candidate.availableCount() || !ingredient.test(candidate.stack())) continue;
                used[slot]++;
                grid.set(gridIndex, candidate.stack().copyWithCount(1));
                if (assignShapeless(ingredientIndex + 1, ingredients, warehouse, used, grid, searchBudget)) return true;
                grid.set(gridIndex, ItemStack.EMPTY);
                used[slot]--;
            }
        }
        return false;
    }

    private static int findIngredient(Ingredient ingredient, List<WarehouseSlot> warehouse, int[] used) {
        for (int index = 0; index < warehouse.size(); index++) {
            WarehouseSlot slot = warehouse.get(index);
            if (used[index] < slot.availableCount() && ingredient.test(slot.stack())) return index;
        }
        return -1;
    }

    private static boolean consumeInput(List<WarehouseSlot> warehouse, CraftingInput input) {
        int[] required = new int[warehouse.size()];
        for (int gridIndex = 0; gridIndex < input.size(); gridIndex++) {
            ItemStack needed = input.getItem(gridIndex);
            if (needed.isEmpty()) continue;
            int match = -1;
            for (int slot = 0; slot < warehouse.size(); slot++) {
                WarehouseSlot candidate = warehouse.get(slot);
                if (candidate.stack().is(needed.getItem())
                        && ItemStack.isSameItemSameComponents(candidate.stack(), needed)
                        && required[slot] < candidate.availableCount()) { match = slot; break; }
            }
            if (match < 0) return false;
            required[match]++;
        }
        for (int slot = 0; slot < warehouse.size(); slot++) {
            int amount = required[slot];
            if (amount <= 0) continue;
            WarehouseSlot candidate = warehouse.get(slot);
            candidate.stack().shrink(amount);
            candidate.container().setChanged();
        }
        return true;
    }

    private static boolean canStore(List<Container> containers, ItemStack offered) {
        int capacity = 0;
        for (Container container : containers) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack existing = container.getItem(slot);
                if (existing.isEmpty()) capacity += offered.getMaxStackSize();
                else if (ItemStack.isSameItemSameComponents(existing, offered)) {
                    capacity += Math.max(0, existing.getMaxStackSize() - existing.getCount());
                }
                if (capacity >= offered.getCount()) return true;
            }
        }
        return false;
    }

    public static int storedSlotCount(List<Container> containers) {
        return containers.stream().mapToInt(container -> {
            int occupied = 0;
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                if (!container.getItem(slot).isEmpty()) occupied++;
            }
            return occupied;
        }).sum();
    }

    public static int slotCapacity(List<Container> containers) {
        return containers.stream().mapToInt(Container::getContainerSize).sum();
    }

    public static int doubleChestCount(ServerLevel level, Settlement settlement) {
        int count = 0;
        for (PairOffset pair : PAIRS) {
            BlockPos first = new BlockPos(settlement.x() + pair.x(), settlement.y() + 1,
                    settlement.z() + pair.z());
            BlockPos second = first.east();
            if (level.hasChunkAt(first) && level.hasChunkAt(second)
                    && level.getBlockState(first).is(Blocks.CHEST) && level.getBlockState(second).is(Blocks.CHEST)) {
                count++;
            }
        }
        return count;
    }

    private static int count(Container container, Item item) {
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private static int count(List<Container> containers, Item item) {
        return containers.stream().mapToInt(container -> count(container, item)).sum();
    }

    private static void remove(Container container, Item item, int count) {
        int remaining = count;
        for (int slot = 0; slot < container.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.is(item)) continue;
            int removed = Math.min(remaining, stack.getCount());
            stack.shrink(removed);
            remaining -= removed;
        }
    }

    private static void remove(List<Container> containers, Item item, int count) {
        int remaining = count;
        for (Container container : containers) {
            int present = count(container, item);
            int removed = Math.min(remaining, present);
            if (removed > 0) remove(container, item, removed);
            remaining -= removed;
            if (remaining <= 0) break;
        }
    }
}
