package com.gblfxt.llmoblings.ai;

import com.gblfxt.llmoblings.entity.CompanionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Builds a compact world-state context string for injection into LLM prompts.
 */
public class WorldStateBuilder {

    /**
     * Build a compact world state summary (~300-500 chars) for the given companion.
     * Example: [WORLD STATE] Pos: (142, 64, -89) | Biome: plains | Light: 15 | HP: 18/20 | Wielding: Iron Sword | Inv: 47 items, 12 free slots | DANGER: zombie x2 @8m
     */
    public static String buildContext(CompanionEntity companion) {
        SpatialAwareness.AreaSummary area = SpatialAwareness.summarizeArea(companion, 24);

        BlockPos pos = area.position();
        StringBuilder sb = new StringBuilder();
        sb.append("[WORLD STATE] ");
        sb.append("Pos: (").append(pos.getX()).append(", ").append(pos.getY()).append(", ").append(pos.getZ()).append(")");
        sb.append(" | Biome: ").append(area.biome());
        sb.append(" | Light: ").append(area.lightLevel());

        // Health
        sb.append(" | HP: ").append(String.format("%.0f/%.0f", companion.getHealth(), companion.getMaxHealth()));

        // Main hand item
        ItemStack mainHand = companion.getMainHandItem();
        if (!mainHand.isEmpty()) {
            sb.append(" | Wielding: ").append(mainHand.getHoverName().getString());
        }

        // Inventory summary
        int usedSlots = 0;
        int totalItems = 0;
        for (int i = 0; i < companion.getContainerSize(); i++) {
            ItemStack stack = companion.getItem(i);
            if (!stack.isEmpty()) {
                usedSlots++;
                totalItems += stack.getCount();
            }
        }
        int freeSlots = companion.getContainerSize() - usedSlots;
        sb.append(" | Inv: ").append(totalItems).append(" items, ").append(freeSlots).append(" free slots");

        // Danger / nearby mobs
        List<SpatialAwareness.MobInfo> mobs = area.nearbyMobs();
        if (!mobs.isEmpty()) {
            if (area.isDangerous()) {
                sb.append(" | DANGER:");
            } else {
                sb.append(" | Nearby:");
            }
            // Show up to 3 most relevant mobs (hostiles first, then closest)
            mobs.stream()
                .sorted((a, b) -> {
                    if (a.hostile() != b.hostile()) return a.hostile() ? -1 : 1;
                    return Double.compare(a.distance(), b.distance());
                })
                .limit(3)
                .forEach(mob -> {
                    sb.append(" ").append(mob.name());
                    if (mob.count() > 1) sb.append(" x").append(mob.count());
                    sb.append(" @").append(String.format("%.0fm", mob.distance()));
                });
        }

        return sb.toString();
    }
}
