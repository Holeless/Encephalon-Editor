package com.example.encephalon.config;

import java.util.List;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Client config (encephalon-client.toml). */
public final class EncephalonConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue SHOW_MOBS_WITHOUT_EGG;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> QUIET_MOBS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> PART_MOBS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> NOT_PART_MOBS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        SHOW_MOBS_WITHOUT_EGG = b
                .comment("List every mob in the Mob Editor, including internal ones.",
                        "When off, vanilla mobs need a spawn egg, and modded mobs need a spawn egg that really shows up in a",
                        "creative tab or have to spawn naturally (biome or structure spawn lists).")
                .translation("encephalon.configuration.showMobsWithoutEgg")
                .define("showMobsWithoutEgg", false);
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
        SPEC = b.build();
    }

    public static boolean showMobsWithoutEgg() {
        return SPEC.isLoaded() && SHOW_MOBS_WITHOUT_EGG.get();
    }

    /** True if the preview should draw this type without ticking it or spawning a server twin for it. */
    public static boolean isQuiet(EntityType<?> type) {
        if (!SPEC.isLoaded()) {
            return false;
        }
        return QUIET_MOBS.get().contains(BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
    }

    public static boolean isForcedPart(EntityType<?> type) {
        return SPEC.isLoaded() && PART_MOBS.get().contains(BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
    }

    public static boolean isForcedNotPart(EntityType<?> type) {
        return SPEC.isLoaded() && NOT_PART_MOBS.get().contains(BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
    }

    private EncephalonConfig() {
    }
}
