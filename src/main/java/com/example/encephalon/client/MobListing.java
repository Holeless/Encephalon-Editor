package com.example.encephalon.client;

import java.util.HashSet;
import java.util.Set;

import com.example.encephalon.EncephalonMod;
import com.example.encephalon.config.EncephalonConfig;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSpawnOverride;

/**
 * Decides which mobs the Mob Editor lists.
 * <p>
 * Vanilla mobs: listed when they have a spawn egg (plus a small whitelist for the ones vanilla has no egg for).
 * Modded mobs: listed when their spawn egg can really be found in a creative tab, or when they spawn naturally (biome spawn
 * lists, including ones added by biome modifiers, and structure spawn overrides). A modded mob that has an egg registered but
 * hidden from every tab and never spawns is almost always an internal entity (a part, a dummy), so it is left out.
 * The config option showMobsWithoutEgg lists everything.
 * <p>
 * Built once when the editor opens (it needs the integrated server for the biome spawn lists).
 */
final class MobListing {
    /** Mobs without a spawn egg that are still always listed (vanilla has no egg for these). */
    private static final Set<String> ALWAYS_LISTED = Set.of("ender_dragon", "wither", "iron_golem", "snow_golem");

    /** Spawn egg items that appear in a creative tab; null if that could not be determined. */
    private final Set<Item> creativeEggs;
    /** Entity types that appear in a biome or structure spawn list; null if that could not be determined. */
    private final Set<EntityType<?>> natural;

    private MobListing(Set<Item> creativeEggs, Set<EntityType<?>> natural) {
        this.creativeEggs = creativeEggs;
        this.natural = natural;
    }

    static MobListing scan(Minecraft mc, MinecraftServer server) {
        return new MobListing(scanCreativeEggs(mc), scanNaturalSpawns(server));
    }

    private static Set<Item> scanCreativeEggs(Minecraft mc) {
        try {
            LocalPlayer player = mc.player;
            if (player == null || mc.level == null) {
                return null;
            }
            // Make sure the tabs are filled (they are built lazily, the first time the creative screen is opened).
            CreativeModeTabs.tryRebuildTabContents(player.connection.enabledFeatures(), player.canUseGameMasterBlocks(),
                    mc.level.registryAccess());
            Set<Item> found = new HashSet<>();
            for (CreativeModeTab tab : BuiltInRegistries.CREATIVE_MODE_TAB) {
                // The search tab lists everything, including items that are hidden from the normal tabs.
                if (tab.getType() != CreativeModeTab.Type.CATEGORY) {
                    continue;
                }
                for (ItemStack stack : tab.getDisplayItems()) {
                    if (stack.getItem() instanceof SpawnEggItem) {
                        found.add(stack.getItem());
                    }
                }
            }
            // Nothing found at all means the tabs were not available: do not hide every modded mob because of that.
            return found.isEmpty() ? null : found;
        } catch (RuntimeException ex) {
            EncephalonMod.LOGGER.warn("Mob Editor: could not read the creative tabs; falling back to 'has a spawn egg'", ex);
            return null;
        }
    }

    private static Set<EntityType<?>> scanNaturalSpawns(MinecraftServer server) {
        if (server == null) {
            return null;
        }
        try {
            Set<EntityType<?>> found = new HashSet<>();
            // The client's biome registry has no spawn lists (they are not synced), so ask the integrated server.
            for (var biome : server.registryAccess().registryOrThrow(Registries.BIOME)) {
                MobSpawnSettings settings = biome.getMobSettings();
                for (MobCategory category : MobCategory.values()) {
                    for (MobSpawnSettings.SpawnerData data : settings.getMobs(category).unwrap()) {
                        found.add(data.type);
                    }
                }
            }
            // Mobs that only spawn inside structures (fortress blazes, monuments, ...).
            for (Structure structure : server.registryAccess().registryOrThrow(Registries.STRUCTURE)) {
                for (StructureSpawnOverride override : structure.spawnOverrides().values()) {
                    for (MobSpawnSettings.SpawnerData data : override.spawns().unwrap()) {
                        found.add(data.type);
                    }
                }
            }
            return found;
        } catch (RuntimeException ex) {
            EncephalonMod.LOGGER.warn("Mob Editor: could not read the natural spawn lists; falling back to 'has a spawn egg'", ex);
            return null;
        }
    }

    boolean isListed(EntityType<?> t) {
        if (EncephalonConfig.showMobsWithoutEgg()) {
            return true;
        }
        ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(t);
        Item egg = SpawnEggItem.byId(t);
        if (key.getNamespace().equals("minecraft")) {
            return egg != null || ALWAYS_LISTED.contains(key.getPath());
        }
        boolean eggShown = egg != null && (creativeEggs == null || creativeEggs.contains(egg));
        boolean spawnsNaturally = natural != null && natural.contains(t);
        return eggShown || spawnsNaturally;
    }
}
