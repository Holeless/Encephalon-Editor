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

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        SHOW_MOBS_WITHOUT_EGG = b
                .comment("Show mobs that have no spawn egg in the Mob Editor.")
                .translation("encephalon.configuration.showMobsWithoutEgg")
                .define("showMobsWithoutEgg", false);
        QUIET_MOBS = b
                .comment("Modded mobs (entity ids) that the Mob Editor preview must treat as inert: no hidden server twin and no",
                        "entity ticking, only the model is drawn. Use this for bosses whose own code starts music, sky effects",
                        "or other things when they merely exist. Example: [\"alexscaves:tremorzilla\"]")
                .defineListAllowEmpty("quietMobs",
                        List.of("alexscaves:luxtructosaurus", "alexscaves:tremorzilla", "alexscaves:hullbreaker"),
                        () -> "modid:entity", o -> o instanceof String);
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

    private EncephalonConfig() {
    }
}
