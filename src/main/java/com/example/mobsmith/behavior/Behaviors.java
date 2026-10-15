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

/**
 * Behavior library (currently empty). Layers: 0 Reflex, 1 Tactical, 2 Traversal, 3 Combat, 4 Idle.
 * Add one with: public static final Behavior X = register("x", Items.FOO, Archetype.GOAL, MOBS, 3, List.of(...));
 */
public final class Behaviors {
    private static final Map<String, Behavior> REGISTRY = new LinkedHashMap<>();
    public static final Set<ScopeType> WORLD_ONLY = EnumSet.of(ScopeType.WORLD);
    public static final Set<ScopeType> MOBS = EnumSet.range(ScopeType.ALL, ScopeType.INDIVIDUAL);

    private Behaviors() {
    }

    public static Behavior register(String path, Item icon, Archetype a, Set<ScopeType> scopes, int layer, List<Setting> settings) {
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
