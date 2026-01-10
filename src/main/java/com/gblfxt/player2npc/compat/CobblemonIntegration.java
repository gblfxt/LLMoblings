package com.gblfxt.player2npc.compat;

import com.gblfxt.player2npc.Player2NPC;
import com.gblfxt.player2npc.entity.CompanionEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

/**
 * Integration with Cobblemon mod for Pokemon companion support.
 */
public class CobblemonIntegration {

    private static Boolean cobblemonLoaded = null;
    private static Class<?> pokemonEntityClass = null;

    public static boolean isCobblemonLoaded() {
        if (cobblemonLoaded == null) {
            cobblemonLoaded = ModList.get().isLoaded("cobblemon");
            if (cobblemonLoaded) {
                Player2NPC.LOGGER.info("Cobblemon mod detected - Pokemon companion support enabled");
                try {
                    pokemonEntityClass = Class.forName("com.cobblemon.mod.common.entity.pokemon.PokemonEntity");
                } catch (ClassNotFoundException e) {
                    Player2NPC.LOGGER.warn("Could not load PokemonEntity class: {}", e.getMessage());
                    pokemonEntityClass = null;
                }
            }
        }
        return cobblemonLoaded;
    }

    /**
     * Check if an entity is a Pokemon.
     */
    public static boolean isPokemon(Entity entity) {
        if (!isCobblemonLoaded() || pokemonEntityClass == null) {
            return false;
        }
        return pokemonEntityClass.isInstance(entity);
    }

    /**
     * Check if a Pokemon is wild (not owned).
     */
    public static boolean isWildPokemon(Entity entity) {
        if (!isPokemon(entity)) {
            return false;
        }

        try {
            // Get the Pokemon data object
            Object pokemon = getPokemonData(entity);
            if (pokemon == null) return true;

            // Call isWild()
            Method isWildMethod = pokemon.getClass().getMethod("isWild");
            return (Boolean) isWildMethod.invoke(pokemon);
        } catch (Exception e) {
            Player2NPC.LOGGER.debug("Error checking if Pokemon is wild: {}", e.getMessage());
            return true;
        }
    }

    /**
     * Get the owner of a Pokemon.
     */
    public static LivingEntity getPokemonOwner(Entity entity) {
        if (!isPokemon(entity)) {
            return null;
        }

        try {
            Object pokemon = getPokemonData(entity);
            if (pokemon == null) return null;

            // Try getOwnerEntity()
            Method getOwnerMethod = pokemon.getClass().getMethod("getOwnerEntity");
            return (LivingEntity) getOwnerMethod.invoke(pokemon);
        } catch (Exception e) {
            Player2NPC.LOGGER.debug("Error getting Pokemon owner: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Get the owner UUID of a Pokemon.
     */
    public static UUID getPokemonOwnerUUID(Entity entity) {
        if (!isPokemon(entity)) {
            return null;
        }

        try {
            Object pokemon = getPokemonData(entity);
            if (pokemon == null) return null;

            Method getOwnerUUIDMethod = pokemon.getClass().getMethod("getOwnerUUID");
            return (UUID) getOwnerUUIDMethod.invoke(pokemon);
        } catch (Exception e) {
            Player2NPC.LOGGER.debug("Error getting Pokemon owner UUID: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Get the Pokemon data object from a PokemonEntity.
     */
    private static Object getPokemonData(Entity entity) {
        try {
            Method getPokemonMethod = entity.getClass().getMethod("getPokemon");
            return getPokemonMethod.invoke(entity);
        } catch (Exception e) {
            Player2NPC.LOGGER.debug("Error getting Pokemon data: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Get Pokemon species name.
     */
    public static String getPokemonSpeciesName(Entity entity) {
        if (!isPokemon(entity)) {
            return null;
        }

        try {
            Object pokemon = getPokemonData(entity);
            if (pokemon == null) return "Unknown Pokemon";

            // Get species
            Method getSpeciesMethod = pokemon.getClass().getMethod("getSpecies");
            Object species = getSpeciesMethod.invoke(pokemon);

            if (species != null) {
                Method getNameMethod = species.getClass().getMethod("getName");
                return (String) getNameMethod.invoke(species);
            }
        } catch (Exception e) {
            Player2NPC.LOGGER.debug("Error getting Pokemon species: {}", e.getMessage());
        }

        return "Pokemon";
    }

    /**
     * Get Pokemon nickname (or species name if no nickname).
     */
    public static String getPokemonDisplayName(Entity entity) {
        if (!isPokemon(entity)) {
            return null;
        }

        try {
            Object pokemon = getPokemonData(entity);
            if (pokemon == null) return getPokemonSpeciesName(entity);

            // Try to get nickname
            Method getNicknameMethod = pokemon.getClass().getMethod("getNickname");
            Object nickname = getNicknameMethod.invoke(pokemon);

            if (nickname != null) {
                Method getStringMethod = nickname.getClass().getMethod("getString");
                String nicknameStr = (String) getStringMethod.invoke(nickname);
                if (nicknameStr != null && !nicknameStr.isEmpty()) {
                    return nicknameStr;
                }
            }
        } catch (Exception e) {
            // Fall through to species name
        }

        return getPokemonSpeciesName(entity);
    }

    /**
     * Get Pokemon level.
     */
    public static int getPokemonLevel(Entity entity) {
        if (!isPokemon(entity)) {
            return 0;
        }

        try {
            Object pokemon = getPokemonData(entity);
            if (pokemon == null) return 0;

            Method getLevelMethod = pokemon.getClass().getMethod("getLevel");
            return (Integer) getLevelMethod.invoke(pokemon);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Check if Pokemon is shiny.
     */
    public static boolean isPokemonShiny(Entity entity) {
        if (!isPokemon(entity)) {
            return false;
        }

        try {
            Object pokemon = getPokemonData(entity);
            if (pokemon == null) return false;

            Method getShinyMethod = pokemon.getClass().getMethod("getShiny");
            return (Boolean) getShinyMethod.invoke(pokemon);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Find nearby Pokemon owned by a specific player.
     */
    public static List<Entity> findPlayerPokemon(CompanionEntity companion, ServerPlayer owner, int radius) {
        if (!isCobblemonLoaded()) {
            return List.of();
        }

        AABB searchBox = companion.getBoundingBox().inflate(radius);
        UUID ownerUUID = owner.getUUID();

        return companion.level().getEntities(companion, searchBox, entity -> {
            if (!isPokemon(entity)) return false;
            if (isWildPokemon(entity)) return false;

            UUID pokemonOwnerUUID = getPokemonOwnerUUID(entity);
            return ownerUUID.equals(pokemonOwnerUUID);
        });
    }

    /**
     * Find the nearest Pokemon owned by the companion's owner.
     */
    public static Entity findNearestPlayerPokemon(CompanionEntity companion, int radius) {
        if (companion.getOwner() == null) return null;
        if (!(companion.getOwner() instanceof ServerPlayer player)) return null;

        List<Entity> playerPokemon = findPlayerPokemon(companion, player, radius);

        return playerPokemon.stream()
                .min((a, b) -> Double.compare(
                        companion.distanceTo(a),
                        companion.distanceTo(b)))
                .orElse(null);
    }

    /**
     * Make a Pokemon follow a target entity (basic navigation).
     */
    public static void makePokemonFollow(Entity pokemon, LivingEntity target) {
        if (!isPokemon(pokemon)) return;

        try {
            // Use navigation to move toward target
            if (pokemon instanceof net.minecraft.world.entity.Mob mob) {
                double speed = 1.0;
                mob.getNavigation().moveTo(target, speed);
            }
        } catch (Exception e) {
            Player2NPC.LOGGER.debug("Error making Pokemon follow: {}", e.getMessage());
        }
    }

    /**
     * Get a summary of a Pokemon for chat responses.
     */
    public static String getPokemonSummary(Entity entity) {
        if (!isPokemon(entity)) {
            return null;
        }

        String name = getPokemonDisplayName(entity);
        String species = getPokemonSpeciesName(entity);
        int level = getPokemonLevel(entity);
        boolean shiny = isPokemonShiny(entity);

        StringBuilder sb = new StringBuilder();
        if (!name.equals(species)) {
            sb.append(name).append(" the ");
        }
        if (shiny) {
            sb.append("shiny ");
        }
        sb.append(species);
        sb.append(" (Lv. ").append(level).append(")");

        return sb.toString();
    }
}
