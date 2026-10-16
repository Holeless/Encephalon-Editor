package com.example.encephalon.config;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Client config (encephalon-client.toml). */
public final class EncephalonConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue SHOW_MOBS_WITHOUT_EGG;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        SHOW_MOBS_WITHOUT_EGG = b
                .comment("List mobs that have no spawn egg (Giant, Illusioner, ...) in the Mob Editor.")
                .translation("encephalon.configuration.showMobsWithoutEgg")
                .define("showMobsWithoutEgg", false);
        SPEC = b.build();
    }

    public static boolean showMobsWithoutEgg() {
        return SPEC.isLoaded() && SHOW_MOBS_WITHOUT_EGG.get();
    }

    private EncephalonConfig() {
    }
}
