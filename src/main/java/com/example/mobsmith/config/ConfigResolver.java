package com.example.mobsmith.config;

import java.util.ArrayList;
import java.util.List;

import com.example.mobsmith.behavior.Behavior;
import com.example.mobsmith.behavior.Setting;
import com.example.mobsmith.scope.Scope;
import com.example.mobsmith.scope.ScopeType;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Mob;

/**
 * Walks scopes broad to specific; the most specific non-inherit entry wins.
 * Handlers call this instead of CurseData.isActive. TODO: cache per entity type, invalidated by MobConfigData.version().
 */
public final class ConfigResolver {
    private ConfigResolver() {
    }

    /** Scopes that apply to a mob, broad to specific. TODO: GROUP scopes (entity type tags / user groups). */
    public static List<Scope> chain(Mob mob) {
        List<Scope> list = new ArrayList<>();
        list.add(Scope.ALL);
        list.add(new Scope(ScopeType.CATEGORY, mob.getType().getCategory().getName()));
        list.add(new Scope(ScopeType.MOB_TYPE, BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString()));
        list.add(new Scope(ScopeType.INDIVIDUAL, mob.getUUID().toString()));
        return list;
    }

    public static boolean enabled(MinecraftServer server, Behavior b) {
        return server != null && MobConfigData.get(server).toggle(Scope.WORLD, b) == Tri.ON;
    }

    public static boolean enabled(Mob mob, Behavior b) {
        MinecraftServer server = mob.getServer();
        if (server == null) {
            return false;
        }
        MobConfigData data = MobConfigData.get(server);
        boolean on = false;
        for (Scope s : chain(mob)) {
            Tri t = data.toggle(s, b);
            if (t != Tri.INHERIT) {
                on = t == Tri.ON;
            }
        }
        return on;
    }

    public static double value(Mob mob, Behavior b, String key) {
        Setting setting = b.setting(key);
        if (setting == null) {
            throw new IllegalArgumentException("No setting " + key + " on " + b.id());
        }
        MinecraftServer server = mob.getServer();
        double result = setting.multiplier() ? 1.0 : setting.def();
        if (server == null) {
            return result;
        }
        MobConfigData data = MobConfigData.get(server);
        for (Scope s : chain(mob)) {
            Double v = data.value(s, b, key);
            if (v != null) {
                result = setting.multiplier() ? result * v : v;
            }
        }
        return setting.clamp(result);
    }

    public record Toggle(boolean on, Scope from) {
    }

    public record Val(double value, Scope from) {
    }

    /** Scopes that apply when looking at a scope in the editor, broad to specific (ending with the scope itself). */
    public static List<Scope> chainFor(Scope s) {
        List<Scope> l = new ArrayList<>();
        switch (s.type()) {
            case WORLD -> l.add(s);
            case ALL -> l.add(s);
            case MOB_TYPE -> {
                l.add(Scope.ALL);
                EntityType<?> t = BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(s.id()));
                l.add(new Scope(ScopeType.CATEGORY, t.getCategory().getName()));
                l.add(s);
            }
            default -> {
                l.add(Scope.ALL);
                l.add(s);
            }
        }
        return l;
    }

    public static Toggle toggleFor(MobConfigData d, List<Scope> chain, Behavior b) {
        boolean on = false;
        Scope from = null;
        for (Scope s : chain) {
            Tri t = d.toggle(s, b);
            if (t != Tri.INHERIT) {
                on = t == Tri.ON;
                from = s;
            }
        }
        return new Toggle(on, from);
    }

    public static Val valueFor(MobConfigData d, List<Scope> chain, Behavior b, Setting set) {
        double v = set.multiplier() ? 1.0 : set.def();
        Scope from = null;
        for (Scope s : chain) {
            Double x = d.value(s, b, set.key());
            if (x != null) {
                v = set.multiplier() ? v * x : x;
                from = s;
            }
        }
        return new Val(set.clamp(v), from);
    }
}
