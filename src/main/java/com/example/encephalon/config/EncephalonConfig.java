package com.example.encephalon.config;

import java.util.List;
import java.util.Set;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.Tags;

/** Client config (encephalon-client.toml). */
public final class EncephalonConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue SHOW_MOBS_WITHOUT_EGG;
    public static final ModConfigSpec.BooleanValue BOSS_TWINS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> QUIET_MOBS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> PART_MOBS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> NOT_PART_MOBS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> NO_TWIN_MODS;

    /**
     * Always treated as quiet, whatever the config file says (a config file made by an older version keeps its old list, so
     * new entries added to the default list would never reach it). Bosses whose own code changes the sky or starts music.
     */
    private static final Set<String> BUILT_IN_QUIET = Set.of(
            "alexscaves:luxtructosaurus", "alexscaves:tremorzilla", "alexscaves:hullbreaker",
            "bosses_of_mass_destruction:lich", "bosses_of_mass_destruction:gauntlet",
            "bosses_of_mass_destruction:obsidilith", "bosses_of_mass_destruction:void_blossom");

    /** Never treated as a body part of a preview mob, whatever the config file says (a portal would be drawn and could do harm). */
    private static final Set<String> BUILT_IN_NOT_PART = Set.of("alexsmobs:void_portal");

    /** Mods whose mobs are single entities (no body segments or detached heads), so they never get a hidden server twin. */
    private static final Set<String> BUILT_IN_NO_TWIN_MODS = Set.of("naturalist");

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        SHOW_MOBS_WITHOUT_EGG = b
                .comment("List every mob in the Mob Editor, including internal ones.",
                        "When off, vanilla mobs need a spawn egg, and modded mobs need a spawn egg that really shows up in a",
                        "creative tab or have to spawn naturally (biome or structure spawn lists).")
                .translation("encephalon.configuration.showMobsWithoutEgg")
                .define("showMobsWithoutEgg", false);
        BOSS_TWINS = b
                .comment("Give modded bosses (tagged c:bosses, or holding a boss bar) a hidden server twin in the preview, so their",
                        "body parts exist and are drawn (the Void Worm needs this). They still are never ticked on the client.",
                        "Turn off if a boss changes the sky or starts music in the editor. Mobs in quietMobs never get a twin.")
                .define("bossTwins", true);
        QUIET_MOBS = b
                .comment("Modded mobs (entity ids) that the Mob Editor preview must treat as inert: no hidden server twin and no",
                        "entity ticking, only the model is drawn. Use this for bosses whose own code starts music, sky effects",
                        "or other things when they merely exist. Example: [\"alexscaves:tremorzilla\"]")
                .defineListAllowEmpty("quietMobs",
                        List.of("alexscaves:luxtructosaurus", "alexscaves:tremorzilla", "alexscaves:hullbreaker"),
                        () -> "modid:entity", o -> o instanceof String);
        PART_MOBS = b
                .comment("Entity ids that the preview must always treat as a body part of the mob that made them (drawn together",
                        "with it). Only needed if a segment of a multi-part mob is wrongly hidden. Example: [\"somemod:worm_segment\"]")
                .defineListAllowEmpty("partMobs", List.of(), () -> "modid:entity", o -> o instanceof String);
        NOT_PART_MOBS = b
                .comment("Entity ids that the preview must never treat as a body part: minions or helpers that a mob spawns with.",
                        "Anything listed here is removed when a preview mob creates it. Example: [\"somemod:minion\"]")
                .defineListAllowEmpty("notPartMobs", List.of(), () -> "modid:entity", o -> o instanceof String);
        NO_TWIN_MODS = b
                .comment("Mod ids whose mobs never get a hidden server twin in the preview (the model is still drawn and ticked on",
                        "the client). A twin is only needed by mods that build one mob out of several entities (the Void Worm's",
                        "segments). \"naturalist\" is always on this list. Example: [\"somemod\"]")
                .defineListAllowEmpty("noTwinMods", List.of(), () -> "modid", o -> o instanceof String);
        SPEC = b.build();
    }

    public static boolean showMobsWithoutEgg() {
        return SPEC.isLoaded() && SHOW_MOBS_WITHOUT_EGG.get();
    }

    /** True if this type is named in the built-in or config quiet lists: no client ticking and no server twin at all. */
    public static boolean isQuietListed(EntityType<?> type) {
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
        return BUILT_IN_QUIET.contains(id) || (SPEC.isLoaded() && QUIET_MOBS.get().contains(id));
    }

    /** True if this type comes from a mod that is known (or configured) to need no server twin. */
    public static boolean isNoTwinMod(EntityType<?> type) {
        String ns = BuiltInRegistries.ENTITY_TYPE.getKey(type).getNamespace();
        return BUILT_IN_NO_TWIN_MODS.contains(ns) || (SPEC.isLoaded() && NO_TWIN_MODS.get().contains(ns));
    }

    public static boolean bossTwins() {
        return !SPEC.isLoaded() || BOSS_TWINS.get();
    }

    /** True if the preview should draw this type without ticking it (listed ones also get no server twin). */
    public static boolean isQuiet(EntityType<?> type) {
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
        if (BUILT_IN_QUIET.contains(id)) {
            return true;
        }
        // Modded bosses (anything tagged c:bosses) are quiet too: they are the mobs that tend to change the sky or start music.
        if (!id.startsWith("minecraft:") && type.is(Tags.EntityTypes.BOSSES)) {
            return true;
        }
        return SPEC.isLoaded() && QUIET_MOBS.get().contains(id);
    }

    public static boolean isForcedPart(EntityType<?> type) {
        return SPEC.isLoaded() && PART_MOBS.get().contains(BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
    }

    public static boolean isForcedNotPart(EntityType<?> type) {
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
        return BUILT_IN_NOT_PART.contains(id) || (SPEC.isLoaded() && NOT_PART_MOBS.get().contains(id));
    }

    private EncephalonConfig() {
    }
}
