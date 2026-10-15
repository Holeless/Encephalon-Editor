package com.example.mobsmith.scope;

import java.util.List;
import java.util.Set;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

/** Sorts entity types into the three editor categories: hostile, neutral, passive. Null means "not a mob". */
public final class MobGroups {
    public static final List<String> CATEGORIES = List.of("hostile", "neutral", "passive");

    private static final Set<String> NEUTRAL = Set.of("bee", "dolphin", "enderman", "goat", "iron_golem", "llama",
            "trader_llama", "panda", "piglin", "polar_bear", "spider", "cave_spider", "wolf", "zombified_piglin", "pufferfish");
    /** Mobs that vanilla files under MISC. */
    private static final Set<String> MISC_MOBS = Set.of("villager", "wandering_trader", "snow_golem");

    private MobGroups() {
    }

    public static String categoryOf(EntityType<?> type) {
        ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        boolean vanilla = key.getNamespace().equals("minecraft");
        if (vanilla && NEUTRAL.contains(key.getPath())) {
            return "neutral";
        }
        return switch (type.getCategory()) {
            case MONSTER -> "hostile";
            case MISC -> vanilla && MISC_MOBS.contains(key.getPath()) ? "passive" : null;
            default -> "passive";
        };
    }
}
