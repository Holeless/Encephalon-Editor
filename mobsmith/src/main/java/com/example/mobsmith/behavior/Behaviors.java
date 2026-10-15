package com.example.mobsmith.behavior;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.example.mobsmith.MobsmithMod;
import com.example.mobsmith.scope.ScopeType;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/** Behavior library. Layers: 0 Reflex, 1 Tactical, 2 Traversal, 3 Combat, 4 Idle. */
public final class Behaviors {
    private static final Map<String, Behavior> REGISTRY = new LinkedHashMap<>();
    private static final Set<ScopeType> WORLD_ONLY = EnumSet.of(ScopeType.WORLD);
    private static final Set<ScopeType> MOBS = EnumSet.range(ScopeType.ALL, ScopeType.INDIVIDUAL);

    public static final Behavior FAMINE = register("famine", Items.BOWL, Archetype.WORLD_RULE, WORLD_ONLY, 4,
            List.of(new Setting("multiplier", 1.5, 1.0, 5.0, "x", false)));
    public static final Behavior BASTION = register("bastion", Items.SHIELD, Archetype.LOADOUT, MOBS, 3,
            List.of(new Setting("chance", 0.15, 0.0, 1.0, "", false)));
    public static final Behavior KITING = register("kiting", Items.BOW, Archetype.GOAL, MOBS, 3,
            List.of(new Setting("backpedal_speed", 1.0, 0.2, 3.0, "x", false),
                    new Setting("range", 7.0, 1.0, 16.0, "blocks", false)));

    private Behaviors() {
    }

    private static Behavior register(String path, Item icon, Archetype a, Set<ScopeType> scopes, int layer, List<Setting> settings) {
        Behavior b = new Behavior(MobsmithMod.id(path), icon, a, scopes, layer, settings);
        REGISTRY.put(path, b);
        return b;
    }

    public static Collection<Behavior> all() {
        return Collections.unmodifiableCollection(REGISTRY.values());
    }

    public static Behavior byName(String name) {
        return REGISTRY.get(name.toLowerCase());
    }
}
