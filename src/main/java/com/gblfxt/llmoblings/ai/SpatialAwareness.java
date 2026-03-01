package com.gblfxt.llmoblings.ai;

import com.gblfxt.llmoblings.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Spatial awareness utilities for LLMoblings companions.
 * Provides environmental scanning similar to nix-cmd functionality,
 * enabling companions to make informed navigation decisions.
 */
public class SpatialAwareness {
    
    /**
     * Result of an edge check operation.
     */
    public record EdgeCheckResult(
        Direction facing,
        boolean edgeFound,
        int safeDistance,
        int dropHeight,
        String description
    ) {}
    
    /**
     * Result of an area scan.
     */
    public record ScanResult(
        Map<Direction, String> surroundings,
        Map<Direction, Boolean> passable,
        String summary
    ) {}
    
    /**
     * Result of a descent option search.
     */
    public record DescentOption(
        String type,  // "ladder", "stairs", "safe_drop", "short_drop"
        Direction direction,
        int distance,
        int dropHeight,
        String landingBlock
    ) {}
    
    /**
     * Comprehensive area summary.
     */
    public record AreaSummary(
        BlockPos position,
        Direction facing,
        int lightLevel,
        int skyLight,
        int blockLight,
        String biome,
        List<MobInfo> nearbyMobs,
        boolean isDangerous
    ) {}
    
    /**
     * Info about a nearby mob.
     */
    public record MobInfo(String name, int count, double distance, boolean hostile) {}
    
    // ========== EDGE CHECK (mirrors /nix edge) ==========
    
    /**
     * Check for cliff edges in the direction the entity is facing.
     * @param entity The entity to check from
     * @param checkDistance How many blocks ahead to check (default 3)
     * @return EdgeCheckResult with details about any edges found
     */
    public static EdgeCheckResult checkEdge(LivingEntity entity, int checkDistance) {
        Level level = entity.level();
        BlockPos pos = entity.blockPosition();
        Direction facing = entity.getDirection();
        
        StringBuilder description = new StringBuilder();
        boolean edgeFound = false;
        int safeDistance = checkDistance;
        int maxDropFound = 0;
        
        for (int dist = 1; dist <= checkDistance; dist++) {
            BlockPos checkPos = pos.relative(facing, dist);
            int dropHeight = getDropHeight(level, checkPos);
            
            if (dropHeight >= 3) {
                edgeFound = true;
                safeDistance = dist - 1;
                maxDropFound = Math.max(maxDropFound, dropHeight);
                description.append("CLIFF at ").append(dist).append(" blocks! Drop: ").append(dropHeight).append("+ blocks. ");
                break;
            } else if (dropHeight > 0) {
                description.append("Step down at ").append(dist).append(" blocks (").append(dropHeight).append(" block drop). ");
            }
        }
        
        if (!edgeFound) {
            description.append("Path clear for ").append(checkDistance).append(" blocks.");
        }
        
        return new EdgeCheckResult(facing, edgeFound, safeDistance, maxDropFound, description.toString().trim());
    }
    
    /**
     * Check for edges in all horizontal directions.
     */
    public static Map<Direction, EdgeCheckResult> checkAllEdges(LivingEntity entity, int checkDistance) {
        Map<Direction, EdgeCheckResult> results = new HashMap<>();
        Level level = entity.level();
        BlockPos pos = entity.blockPosition();
        
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            boolean edgeFound = false;
            int safeDistance = checkDistance;
            int maxDrop = 0;
            StringBuilder desc = new StringBuilder();
            
            for (int dist = 1; dist <= checkDistance; dist++) {
                BlockPos checkPos = pos.relative(dir, dist);
                int dropHeight = getDropHeight(level, checkPos);
                
                if (dropHeight >= 3) {
                    edgeFound = true;
                    safeDistance = dist - 1;
                    maxDrop = dropHeight;
                    desc.append("Cliff at ").append(dist).append(" (").append(dropHeight).append(" drop)");
                    break;
                }
            }
            
            if (!edgeFound) {
                desc.append("Clear");
            }
            
            results.put(dir, new EdgeCheckResult(dir, edgeFound, safeDistance, maxDrop, desc.toString()));
        }
        
        return results;
    }
    
    // ========== SCAN (mirrors /nix scan) ==========
    
    /**
     * Scan immediately adjacent blocks for passability.
     */
    public static ScanResult scanAround(LivingEntity entity) {
        Level level = entity.level();
        BlockPos pos = entity.blockPosition();
        
        Map<Direction, String> surroundings = new HashMap<>();
        Map<Direction, Boolean> passable = new HashMap<>();
        StringBuilder summary = new StringBuilder();
        
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos checkPos = pos.relative(dir);
            BlockState state = level.getBlockState(checkPos);
            BlockState above = level.getBlockState(checkPos.above());
            
            String blockName = state.getBlock().getName().getString();
            boolean canPass = (!state.isSolid() || state.isAir()) && 
                              (!above.isSolid() || above.isAir());
            
            surroundings.put(dir, blockName);
            passable.put(dir, canPass);
            
            summary.append(dir.getName()).append(": ").append(blockName);
            summary.append(canPass ? " [pass]" : " [block]");
            summary.append("; ");
        }
        
        return new ScanResult(surroundings, passable, summary.toString().trim());
    }
    
    // ========== FLOOR CHECK (mirrors /nix floor) ==========
    
    /**
     * Check the floor in a 3x3 area around the entity.
     * @return 2D array where true = solid ground, false = air/void
     */
    public static boolean[][] checkFloor(LivingEntity entity) {
        Level level = entity.level();
        BlockPos pos = entity.blockPosition();
        boolean[][] floor = new boolean[3][3];
        
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos checkPos = pos.offset(dx, -1, dz);
                BlockState state = level.getBlockState(checkPos);
                floor[dx + 1][dz + 1] = !state.isAir();
            }
        }
        
        return floor;
    }
    
    /**
     * Get floor check as a visual string (for debugging/display).
     */
    public static String getFloorVisual(LivingEntity entity) {
        boolean[][] floor = checkFloor(entity);
        StringBuilder sb = new StringBuilder();
        
        for (int z = 0; z < 3; z++) {
            for (int x = 0; x < 3; x++) {
                sb.append(floor[x][z] ? "■" : "□");
            }
            if (z < 2) sb.append("\n");
        }
        
        return sb.toString();
    }
    
    // ========== WALK SAFETY (mirrors /nix walk) ==========
    
    /**
     * Determine safe walking distance in a direction.
     * @param entity The entity
     * @param direction The direction to walk
     * @param maxBlocks Maximum blocks to check
     * @return Number of safe blocks to walk (0 if blocked)
     */
    public static int getSafeWalkDistance(LivingEntity entity, Direction direction, int maxBlocks) {
        Level level = entity.level();
        BlockPos pos = entity.blockPosition();
        int safeBlocks = 0;
        
        for (int i = 1; i <= maxBlocks; i++) {
            BlockPos nextPos = pos.relative(direction, i);
            
            // Check passability (2 blocks high)
            BlockState footLevel = level.getBlockState(nextPos);
            BlockState headLevel = level.getBlockState(nextPos.above());
            
            if (footLevel.isSolid() || headLevel.isSolid()) {
                break; // Blocked
            }
            
            // Check for cliff
            int dropHeight = getDropHeight(level, nextPos);
            if (dropHeight >= 3) {
                break; // Cliff edge
            }
            
            safeBlocks = i;
        }
        
        return safeBlocks;
    }
    
    /**
     * Get the safest direction to move.
     */
    public static Direction getSafestDirection(LivingEntity entity, int checkDistance) {
        Direction best = null;
        int bestDistance = 0;
        
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            int safe = getSafeWalkDistance(entity, dir, checkDistance);
            if (safe > bestDistance) {
                bestDistance = safe;
                best = dir;
            }
        }
        
        return best;
    }
    
    // ========== DESCEND OPTIONS (mirrors /nix descend) ==========
    
    /**
     * Find descent options (stairs, ladders, safe drops) within range.
     */
    public static List<DescentOption> findDescentOptions(LivingEntity entity, int radius) {
        List<DescentOption> options = new ArrayList<>();
        Level level = entity.level();
        BlockPos pos = entity.blockPosition();
        
        // Search for ladders and stairs
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -3; dy <= 0; dy++) {
                    BlockPos checkPos = pos.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(checkPos);
                    
                    if (state.getBlock() instanceof LadderBlock) {
                        Direction dir = getRelativeDirection(pos, checkPos);
                        options.add(new DescentOption("ladder", dir, 
                            Math.max(Math.abs(dx), Math.abs(dz)), 0, "ladder"));
                    }
                    
                    if (state.getBlock() instanceof StairBlock) {
                        Direction dir = getRelativeDirection(pos, checkPos);
                        options.add(new DescentOption("stairs", dir,
                            Math.max(Math.abs(dx), Math.abs(dz)), 0, "stairs"));
                    }
                }
            }
        }
        
        // Check for safe drops (water, hay, slime, powder snow)
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            for (int dist = 1; dist <= 3; dist++) {
                BlockPos edgePos = pos.relative(dir, dist);
                int dropHeight = getDropHeight(level, edgePos);
                
                if (dropHeight >= 3) {
                    BlockPos bottomPos = edgePos.below(dropHeight);
                    BlockState bottomState = level.getBlockState(bottomPos);
                    
                    boolean safeLanding = bottomState.is(Blocks.WATER) ||
                                         bottomState.is(Blocks.HAY_BLOCK) ||
                                         bottomState.is(Blocks.SLIME_BLOCK) ||
                                         bottomState.is(Blocks.POWDER_SNOW);
                    
                    String landingBlock = bottomState.getBlock().getName().getString();
                    
                    if (safeLanding) {
                        options.add(new DescentOption("safe_drop", dir, dist, dropHeight, landingBlock));
                    } else if (dropHeight <= 4) {
                        options.add(new DescentOption("short_drop", dir, dist, dropHeight, landingBlock));
                    }
                }
            }
        }
        
        return options;
    }
    
    // ========== AREA SUMMARY (mirrors /nix around) ==========
    
    /**
     * Get comprehensive area summary for decision making.
     */
    public static AreaSummary summarizeArea(LivingEntity entity, int mobScanRadius) {
        Level level = entity.level();
        BlockPos pos = entity.blockPosition();
        Direction facing = entity.getDirection();
        
        // Light levels
        int skyLight = level.getBrightness(LightLayer.SKY, pos);
        int blockLight = level.getBrightness(LightLayer.BLOCK, pos);
        int lightLevel = Math.max(skyLight, blockLight);
        
        // Biome
        String biome = level.getBiome(pos).unwrapKey()
            .map(key -> key.location().getPath())
            .orElse("unknown");
        
        // Nearby mobs
        AABB searchBox = new AABB(pos).inflate(mobScanRadius);
        List<LivingEntity> nearbyEntities = level.getEntitiesOfClass(LivingEntity.class, searchBox,
            e -> e != entity && !(e instanceof Player) && !(e instanceof CompanionEntity));
        
        // Group and count mobs
        Map<String, MobCountInfo> mobCounts = new HashMap<>();
        for (LivingEntity mob : nearbyEntities) {
            String name = mob.getType().getDescription().getString();
            double dist = entity.distanceTo(mob);
            boolean hostile = mob instanceof net.minecraft.world.entity.monster.Monster;
            
            mobCounts.compute(name, (k, v) -> {
                if (v == null) return new MobCountInfo(1, dist, hostile);
                return new MobCountInfo(v.count + 1, Math.min(v.closestDistance, dist), v.hostile);
            });
        }
        
        List<MobInfo> mobInfoList = new ArrayList<>();
        boolean isDangerous = false;
        for (var entry : mobCounts.entrySet()) {
            MobCountInfo info = entry.getValue();
            mobInfoList.add(new MobInfo(entry.getKey(), info.count, info.closestDistance, info.hostile));
            if (info.hostile && info.closestDistance < 16) {
                isDangerous = true;
            }
        }
        
        return new AreaSummary(pos, facing, lightLevel, skyLight, blockLight, biome, mobInfoList, isDangerous);
    }
    
    private record MobCountInfo(int count, double closestDistance, boolean hostile) {}
    
    // ========== HELPER METHODS ==========
    
    private static int getDropHeight(Level level, BlockPos pos) {
        int drop = 0;
        BlockPos check = pos.below();
        while (drop < 20 && !level.getBlockState(check).isSolid()) {
            drop++;
            check = check.below();
        }
        return drop;
    }
    
    private static Direction getRelativeDirection(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        } else {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
    }
    
    /**
     * Parse a direction string (including relative directions) to a Direction.
     * @param dirStr Direction string (north/south/east/west/forward/back/left/right)
     * @param facing The entity's current facing direction (for relative directions)
     * @return The Direction, or null if invalid
     */
    public static Direction parseDirection(String dirStr, Direction facing) {
        return switch (dirStr.toLowerCase()) {
            case "north", "n" -> Direction.NORTH;
            case "south", "s" -> Direction.SOUTH;
            case "east", "e" -> Direction.EAST;
            case "west", "w" -> Direction.WEST;
            case "forward", "f" -> facing;
            case "back", "backward", "b" -> facing.getOpposite();
            case "left", "l" -> facing.getCounterClockWise();
            case "right", "r" -> facing.getClockWise();
            default -> null;
        };
    }
    
    /**
     * Get yaw angle for a cardinal direction.
     */
    public static float getYawForDirection(Direction dir) {
        return switch (dir) {
            case NORTH -> 180.0f;
            case SOUTH -> 0.0f;
            case EAST -> -90.0f;
            case WEST -> 90.0f;
            default -> 0.0f;
        };
    }
}
