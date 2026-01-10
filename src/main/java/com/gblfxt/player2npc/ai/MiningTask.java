package com.gblfxt.player2npc.ai;

import com.gblfxt.player2npc.Player2NPC;
import com.gblfxt.player2npc.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;

public class MiningTask {
    private final CompanionEntity companion;
    private final String targetBlockName;
    private final int targetCount;
    private final int searchRadius;

    private int minedCount = 0;
    private BlockPos currentTarget = null;
    private int miningProgress = 0;
    private int ticksAtCurrentBlock = 0;
    private int ticksSinceLastProgress = 0;
    private boolean completed = false;
    private boolean failed = false;
    private String failReason = null;

    // Block types that match the request
    private final Set<Block> targetBlocks = new HashSet<>();

    // Mining speeds (ticks to break)
    private static final int BASE_MINING_TICKS = 30; // About 1.5 seconds base

    public MiningTask(CompanionEntity companion, String blockName, int count, int searchRadius) {
        this.companion = companion;
        this.targetBlockName = blockName.toLowerCase();
        this.targetCount = count;
        this.searchRadius = searchRadius;

        resolveTargetBlocks();

        if (targetBlocks.isEmpty()) {
            failed = true;
            failReason = "I don't know what '" + blockName + "' is.";
        }
    }

    private void resolveTargetBlocks() {
        // Try to match block by name (partial matching for convenience)
        String searchTerm = targetBlockName.replace(" ", "_");

        // Common aliases
        Map<String, String> aliases = Map.of(
            "wood", "oak_log",
            "logs", "oak_log",
            "stone", "stone",
            "cobble", "cobblestone",
            "dirt", "dirt",
            "iron", "iron_ore",
            "gold", "gold_ore",
            "diamond", "diamond_ore",
            "coal", "coal_ore",
            "copper", "copper_ore"
        );

        if (aliases.containsKey(searchTerm)) {
            searchTerm = aliases.get(searchTerm);
        }

        // Search all registered blocks
        for (var entry : BuiltInRegistries.BLOCK.entrySet()) {
            ResourceLocation id = entry.getKey().location();
            String blockId = id.getPath();

            // Match if the block ID contains our search term
            if (blockId.contains(searchTerm) || searchTerm.contains(blockId)) {
                targetBlocks.add(entry.getValue());
            }
        }

        // Special handling for "log" to get all log types
        if (searchTerm.contains("log") || searchTerm.equals("wood")) {
            for (var entry : BuiltInRegistries.BLOCK.entrySet()) {
                String blockId = entry.getKey().location().getPath();
                if (blockId.endsWith("_log") || blockId.endsWith("_wood")) {
                    targetBlocks.add(entry.getValue());
                }
            }
        }

        // Special handling for ores
        if (searchTerm.contains("ore")) {
            for (var entry : BuiltInRegistries.BLOCK.entrySet()) {
                String blockId = entry.getKey().location().getPath();
                if (blockId.contains(searchTerm.replace("_ore", "")) && blockId.contains("ore")) {
                    targetBlocks.add(entry.getValue());
                }
            }
        }

        Player2NPC.LOGGER.debug("Resolved '{}' to {} block types", targetBlockName, targetBlocks.size());
    }

    public void tick() {
        if (completed || failed) {
            return;
        }

        // Check if we've collected enough
        if (minedCount >= targetCount) {
            completed = true;
            return;
        }

        // Pick up nearby items
        pickupNearbyItems();

        // If no current target, find one
        if (currentTarget == null || !isValidTarget(currentTarget)) {
            currentTarget = findNearestTargetBlock();
            miningProgress = 0;
            ticksAtCurrentBlock = 0;

            if (currentTarget == null) {
                ticksSinceLastProgress++;
                if (ticksSinceLastProgress > 200) { // 10 seconds without finding anything
                    failed = true;
                    failReason = "I can't find any more " + targetBlockName + " nearby.";
                }
                return;
            }
        }

        ticksSinceLastProgress = 0;

        // Move towards target
        double distance = companion.position().distanceTo(Vec3.atCenterOf(currentTarget));

        if (distance > 4.0) {
            // Too far, pathfind to it
            if (companion.getNavigation().isDone()) {
                companion.getNavigation().moveTo(
                    currentTarget.getX() + 0.5,
                    currentTarget.getY(),
                    currentTarget.getZ() + 0.5,
                    1.0
                );
            }
            ticksAtCurrentBlock = 0;
        } else if (distance > 2.5) {
            // Getting close, keep moving
            companion.getNavigation().moveTo(
                currentTarget.getX() + 0.5,
                currentTarget.getY(),
                currentTarget.getZ() + 0.5,
                0.8
            );
            ticksAtCurrentBlock++;
        } else {
            // In range, mine the block
            companion.getNavigation().stop();
            ticksAtCurrentBlock++;

            // Look at the block
            companion.getLookControl().setLookAt(
                currentTarget.getX() + 0.5,
                currentTarget.getY() + 0.5,
                currentTarget.getZ() + 0.5
            );

            // Swing arm for visual feedback
            if (ticksAtCurrentBlock % 5 == 0) {
                companion.swing(companion.getUsedItemHand());
            }

            // Calculate mining time based on block hardness
            BlockState state = companion.level().getBlockState(currentTarget);
            int miningTime = calculateMiningTime(state);

            miningProgress++;

            if (miningProgress >= miningTime) {
                // Break the block
                breakBlock(currentTarget);
                minedCount++;
                currentTarget = null;
                miningProgress = 0;
            }
        }

        // Timeout if stuck at one block too long
        if (ticksAtCurrentBlock > 300) { // 15 seconds
            Player2NPC.LOGGER.debug("Mining timeout, skipping block at {}", currentTarget);
            currentTarget = null;
            miningProgress = 0;
            ticksAtCurrentBlock = 0;
        }
    }

    private boolean isValidTarget(BlockPos pos) {
        BlockState state = companion.level().getBlockState(pos);
        return targetBlocks.contains(state.getBlock());
    }

    private BlockPos findNearestTargetBlock() {
        BlockPos companionPos = companion.blockPosition();
        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;

        // Search in expanding shells for efficiency
        for (int radius = 1; radius <= searchRadius; radius++) {
            for (int x = -radius; x <= radius; x++) {
                for (int y = -radius; y <= radius; y++) {
                    for (int z = -radius; z <= radius; z++) {
                        // Only check outer shell of this radius
                        if (Math.abs(x) != radius && Math.abs(y) != radius && Math.abs(z) != radius) {
                            continue;
                        }

                        BlockPos checkPos = companionPos.offset(x, y, z);
                        BlockState state = companion.level().getBlockState(checkPos);

                        if (targetBlocks.contains(state.getBlock())) {
                            double dist = companionPos.distSqr(checkPos);
                            if (dist < nearestDist) {
                                // Check if reachable (not surrounded by blocks)
                                if (isReachable(checkPos)) {
                                    nearest = checkPos;
                                    nearestDist = dist;
                                }
                            }
                        }
                    }
                }
            }

            // If we found something in this shell, return it
            if (nearest != null) {
                return nearest;
            }
        }

        return nearest;
    }

    private boolean isReachable(BlockPos pos) {
        // Check if there's an air block adjacent that the companion could stand near
        for (BlockPos adjacent : new BlockPos[]{
            pos.above(), pos.below(), pos.north(), pos.south(), pos.east(), pos.west()
        }) {
            BlockState state = companion.level().getBlockState(adjacent);
            if (!state.isSolid() || state.isAir()) {
                return true;
            }
        }
        return false;
    }

    private int calculateMiningTime(BlockState state) {
        float hardness = state.getDestroySpeed(companion.level(), currentTarget);
        if (hardness < 0) {
            return 1000; // Unbreakable
        }
        // Scale mining time with hardness
        return (int) (BASE_MINING_TICKS + hardness * 10);
    }

    private void breakBlock(BlockPos pos) {
        if (!(companion.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        BlockState state = serverLevel.getBlockState(pos);

        // Get drops
        List<ItemStack> drops = Block.getDrops(state, serverLevel, pos, null, companion, ItemStack.EMPTY);

        // Spawn drops
        for (ItemStack drop : drops) {
            ItemEntity itemEntity = new ItemEntity(
                serverLevel,
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                drop
            );
            itemEntity.setDefaultPickUpDelay();
            serverLevel.addFreshEntity(itemEntity);
        }

        // Remove the block
        serverLevel.destroyBlock(pos, false, companion);

        Player2NPC.LOGGER.debug("Companion mined {} at {}", state.getBlock(), pos);
    }

    private void pickupNearbyItems() {
        AABB pickupBox = companion.getBoundingBox().inflate(3.0);
        List<ItemEntity> items = companion.level().getEntitiesOfClass(ItemEntity.class, pickupBox);

        for (ItemEntity item : items) {
            if (item.isAlive() && !item.hasPickUpDelay()) {
                ItemStack stack = item.getItem();

                // Try to add to companion inventory
                ItemStack remaining = companion.addToInventory(stack);

                if (remaining.isEmpty()) {
                    item.discard();
                } else {
                    item.setItem(remaining);
                }
            }
        }
    }

    public boolean isCompleted() {
        return completed;
    }

    public boolean isFailed() {
        return failed;
    }

    public String getFailReason() {
        return failReason;
    }

    public int getMinedCount() {
        return minedCount;
    }

    public int getTargetCount() {
        return targetCount;
    }

    public String getTargetBlockName() {
        return targetBlockName;
    }

    public BlockPos getCurrentTarget() {
        return currentTarget;
    }
}
