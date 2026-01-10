package com.gblfxt.player2npc.compat;

import com.gblfxt.player2npc.Player2NPC;
import com.gblfxt.player2npc.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Integration with Applied Energistics 2 for accessing ME storage networks.
 * Uses reflection to avoid hard dependency on AE2.
 */
public class AE2Integration {

    private static Boolean ae2Loaded = null;

    public static boolean isAE2Loaded() {
        if (ae2Loaded == null) {
            ae2Loaded = ModList.get().isLoaded("ae2");
            if (ae2Loaded) {
                Player2NPC.LOGGER.info("AE2 detected - ME network integration enabled");
            }
        }
        return ae2Loaded;
    }

    /**
     * Find ME network access points (terminals preferred) near the companion.
     * Prioritizes actual terminals over buses and interfaces.
     */
    public static List<BlockPos> findMEAccessPoints(Level level, BlockPos center, int radius) {
        List<BlockPos> terminals = new ArrayList<>();
        List<BlockPos> otherAccess = new ArrayList<>();

        if (!isAE2Loaded()) {
            return terminals;
        }

        try {
            for (int x = -radius; x <= radius; x++) {
                for (int y = -5; y <= 5; y++) {
                    for (int z = -radius; z <= radius; z++) {
                        BlockPos pos = center.offset(x, y, z);
                        BlockEntity be = level.getBlockEntity(pos);

                        if (be != null) {
                            int priority = getMEAccessPriority(be);
                            if (priority == 1) {
                                terminals.add(pos);  // High priority - terminals
                            } else if (priority == 2) {
                                otherAccess.add(pos);  // Lower priority - interfaces, chests
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Player2NPC.LOGGER.debug("Error scanning for ME access points: {}", e.getMessage());
        }

        // Return terminals first, then other access points
        if (!terminals.isEmpty()) {
            Player2NPC.LOGGER.info("AE2: Found {} terminals", terminals.size());
            return terminals;
        }
        if (!otherAccess.isEmpty()) {
            Player2NPC.LOGGER.info("AE2: No terminals, using {} other access points", otherAccess.size());
        }
        return otherAccess;
    }

    /**
     * Get priority of ME access point.
     * Returns: 1 = terminal (best), 2 = interface/chest, 0 = not an access point
     */
    private static int getMEAccessPriority(BlockEntity be) {
        String className = be.getClass().getName().toLowerCase();

        if (!className.contains("appeng")) {
            return 0;
        }

        // Priority 1: Actual terminals players use
        if (className.contains("terminal") || className.contains("craftingmonitor")) {
            // Exclude part-based terminals that might not be accessible
            // Actually include them - terminals are the best access points
            Player2NPC.LOGGER.debug("AE2: Found terminal: {}", be.getClass().getSimpleName());
            return 1;
        }

        // Priority 2: ME Chests, Drives, Interfaces (can access grid but less ideal)
        if (className.contains("chest") || className.contains("drive")) {
            Player2NPC.LOGGER.debug("AE2: Found chest/drive: {}", be.getClass().getSimpleName());
            return 2;
        }

        // Skip buses - they don't provide good grid access for item retrieval
        if (className.contains("bus") || className.contains("import") || className.contains("export")) {
            Player2NPC.LOGGER.debug("AE2: Skipping bus: {}", be.getClass().getSimpleName());
            return 0;
        }

        // Priority 2: Generic interface
        if (className.contains("interface") || className.contains("pattern")) {
            Player2NPC.LOGGER.debug("AE2: Found interface/pattern: {}", be.getClass().getSimpleName());
            return 2;
        }

        return 0;
    }

    /**
     * Try to extract items from an ME network.
     * Returns items that were successfully extracted.
     */
    public static List<ItemStack> extractItems(Level level, BlockPos accessPoint,
                                                java.util.function.Predicate<ItemStack> filter, int maxCount) {
        List<ItemStack> extracted = new ArrayList<>();

        if (!isAE2Loaded()) {
            return extracted;
        }

        try {
            BlockEntity be = level.getBlockEntity(accessPoint);
            if (be == null) return extracted;

            // Use reflection to access AE2's grid system
            Object grid = getGridFromBlockEntity(be);
            if (grid == null) {
                Player2NPC.LOGGER.info("AE2: Could not get grid from block entity at {}", accessPoint);
                return extracted;
            }

            Object storageService = getStorageService(grid);
            if (storageService == null) {
                Player2NPC.LOGGER.info("AE2: Could not get storage service from grid");
                return extracted;
            }

            Object inventory = getInventory(storageService);
            if (inventory == null) {
                Player2NPC.LOGGER.info("AE2: Could not get inventory from storage service");
                return extracted;
            }

            // Get available stacks and extract matching ones
            extracted = extractMatchingItems(inventory, filter, maxCount);
            Player2NPC.LOGGER.info("AE2: Extracted {} item stacks from ME network", extracted.size());

        } catch (Exception e) {
            Player2NPC.LOGGER.warn("Error extracting from ME network: {}", e.getMessage());
            e.printStackTrace();
        }

        return extracted;
    }

    /**
     * Query what items are available in the ME network.
     */
    public static List<ItemStack> queryAvailableItems(Level level, BlockPos accessPoint,
                                                       java.util.function.Predicate<ItemStack> filter) {
        List<ItemStack> available = new ArrayList<>();

        if (!isAE2Loaded()) {
            return available;
        }

        try {
            BlockEntity be = level.getBlockEntity(accessPoint);
            if (be == null) return available;

            Object grid = getGridFromBlockEntity(be);
            if (grid == null) return available;

            Object storageService = getStorageService(grid);
            if (storageService == null) return available;

            Object inventory = getInventory(storageService);
            if (inventory == null) return available;

            available = queryItems(inventory, filter);

        } catch (Exception e) {
            Player2NPC.LOGGER.debug("Error querying ME network: {}", e.getMessage());
        }

        return available;
    }

    // Reflection helpers to access AE2 internals

    private static Object getGridFromBlockEntity(BlockEntity be) {
        try {
            // Try to get IGridNode from the block entity
            for (var method : be.getClass().getMethods()) {
                if (method.getName().equals("getGridNode") || method.getName().equals("getMainNode")) {
                    method.setAccessible(true);
                    Object node = method.invoke(be, (Object[]) null);
                    if (node != null) {
                        // Get grid from node
                        var getGridMethod = node.getClass().getMethod("getGrid");
                        return getGridMethod.invoke(node);
                    }
                }
            }

            // Try direct grid access
            for (var method : be.getClass().getMethods()) {
                if (method.getName().equals("getGrid")) {
                    method.setAccessible(true);
                    return method.invoke(be);
                }
            }
        } catch (Exception e) {
            Player2NPC.LOGGER.trace("Could not get grid from {}: {}", be.getClass().getSimpleName(), e.getMessage());
        }
        return null;
    }

    private static Object getStorageService(Object grid) {
        try {
            // IGrid.getStorageService() or getService(IStorageService.class)
            for (var method : grid.getClass().getMethods()) {
                if (method.getName().equals("getStorageService")) {
                    return method.invoke(grid);
                }
            }

            // Try generic getService method
            for (var method : grid.getClass().getMethods()) {
                if (method.getName().equals("getService") && method.getParameterCount() == 1) {
                    // Find IStorageService class
                    Class<?> storageServiceClass = Class.forName("appeng.api.networking.storage.IStorageService");
                    return method.invoke(grid, storageServiceClass);
                }
            }
        } catch (Exception e) {
            Player2NPC.LOGGER.trace("Could not get storage service: {}", e.getMessage());
        }
        return null;
    }

    private static Object getInventory(Object storageService) {
        try {
            for (var method : storageService.getClass().getMethods()) {
                if (method.getName().equals("getInventory") && method.getParameterCount() == 0) {
                    return method.invoke(storageService);
                }
            }
        } catch (Exception e) {
            Player2NPC.LOGGER.trace("Could not get inventory: {}", e.getMessage());
        }
        return null;
    }

    private static List<ItemStack> extractMatchingItems(Object inventory,
                                                         java.util.function.Predicate<ItemStack> filter,
                                                         int maxCount) {
        List<ItemStack> extracted = new ArrayList<>();

        try {
            // Get available stacks via getAvailableStacks()
            var getStacksMethod = inventory.getClass().getMethod("getAvailableStacks");
            Object keyCounter = getStacksMethod.invoke(inventory);

            if (keyCounter == null) return extracted;

            // Iterate through available items
            // This is complex because AE2 uses AEKey system, not direct ItemStacks
            // For simplicity, we'll try to get the item representation

            var iteratorMethod = keyCounter.getClass().getMethod("iterator");
            var iterator = iteratorMethod.invoke(keyCounter);

            int extractedCount = 0;
            while (extractedCount < maxCount) {
                var hasNextMethod = iterator.getClass().getMethod("hasNext");
                if (!(boolean) hasNextMethod.invoke(iterator)) break;

                var nextMethod = iterator.getClass().getMethod("next");
                Object entry = nextMethod.invoke(iterator);

                // Get the key and amount
                var getKeyMethod = entry.getClass().getMethod("getKey");
                Object key = getKeyMethod.invoke(entry);

                // Check if it's an item key
                if (key != null && key.getClass().getName().contains("AEItemKey")) {
                    var toStackMethod = key.getClass().getMethod("toStack");
                    ItemStack stack = (ItemStack) toStackMethod.invoke(key);

                    if (!stack.isEmpty() && filter.test(stack)) {
                        // Try to extract
                        var getLongValueMethod = entry.getClass().getMethod("getLongValue");
                        long available = (long) getLongValueMethod.invoke(entry);

                        int toExtract = (int) Math.min(available, Math.min(stack.getMaxStackSize(), maxCount - extractedCount));

                        // Actually extract from the network
                        ItemStack extractedStack = extractFromNetwork(inventory, key, toExtract);
                        if (!extractedStack.isEmpty()) {
                            extracted.add(extractedStack);
                            extractedCount += extractedStack.getCount();
                        }
                    }
                }
            }

        } catch (Exception e) {
            Player2NPC.LOGGER.debug("Error extracting items: {}", e.getMessage());
        }

        return extracted;
    }

    private static ItemStack extractFromNetwork(Object inventory, Object key, int amount) {
        try {
            // MEStorage.extract(AEKey, long, Actionable, IActionSource)
            var extractMethod = inventory.getClass().getMethod("extract",
                    Class.forName("appeng.api.stacks.AEKey"),
                    long.class,
                    Class.forName("appeng.api.config.Actionable"),
                    Class.forName("appeng.api.networking.security.IActionSource"));

            // Get Actionable.MODULATE (actually perform the action)
            Class<?> actionableClass = Class.forName("appeng.api.config.Actionable");
            Object modulate = actionableClass.getField("MODULATE").get(null);

            // Create a BaseActionSource
            Class<?> actionSourceClass = Class.forName("appeng.api.networking.security.IActionSource");
            Object actionSource = Class.forName("appeng.me.helpers.BaseActionSource")
                    .getDeclaredConstructor().newInstance();

            long extracted = (long) extractMethod.invoke(inventory, key, (long) amount, modulate, actionSource);

            if (extracted > 0) {
                var toStackMethod = key.getClass().getMethod("toStack", int.class);
                return (ItemStack) toStackMethod.invoke(key, (int) extracted);
            }
        } catch (Exception e) {
            Player2NPC.LOGGER.trace("Could not extract from network: {}", e.getMessage());
        }

        return ItemStack.EMPTY;
    }

    private static List<ItemStack> queryItems(Object inventory, java.util.function.Predicate<ItemStack> filter) {
        List<ItemStack> items = new ArrayList<>();

        try {
            var getStacksMethod = inventory.getClass().getMethod("getAvailableStacks");
            Object keyCounter = getStacksMethod.invoke(inventory);

            if (keyCounter == null) return items;

            var iteratorMethod = keyCounter.getClass().getMethod("iterator");
            var iterator = iteratorMethod.invoke(keyCounter);

            while (true) {
                var hasNextMethod = iterator.getClass().getMethod("hasNext");
                if (!(boolean) hasNextMethod.invoke(iterator)) break;

                var nextMethod = iterator.getClass().getMethod("next");
                Object entry = nextMethod.invoke(iterator);

                var getKeyMethod = entry.getClass().getMethod("getKey");
                Object key = getKeyMethod.invoke(entry);

                if (key != null && key.getClass().getName().contains("AEItemKey")) {
                    var toStackMethod = key.getClass().getMethod("toStack");
                    ItemStack stack = (ItemStack) toStackMethod.invoke(key);

                    if (!stack.isEmpty() && filter.test(stack)) {
                        var getLongValueMethod = entry.getClass().getMethod("getLongValue");
                        long amount = (long) getLongValueMethod.invoke(entry);
                        stack.setCount((int) Math.min(amount, stack.getMaxStackSize()));
                        items.add(stack);
                    }
                }
            }
        } catch (Exception e) {
            Player2NPC.LOGGER.debug("Error querying items: {}", e.getMessage());
        }

        return items;
    }
}
