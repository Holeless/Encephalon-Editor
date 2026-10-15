package com.example.mobsmith.config;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import com.example.mobsmith.behavior.Behavior;
import com.example.mobsmith.scope.Scope;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

/** Per-world overrides: toggles and setting values, keyed by scope + behavior (+ setting). Saved as data/mobsmith_config.dat. */
public class MobConfigData extends SavedData {
    private static final String FILE = "mobsmith_config";
    private final Map<String, Tri> toggles = new ConcurrentHashMap<>();
    private final Map<String, Double> values = new ConcurrentHashMap<>();
    /** Bumped on every edit so resolved-config caches know when to rebuild. */
    private volatile int version;

    public static MobConfigData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(MobConfigData::new, MobConfigData::load, DataFixTypes.LEVEL), FILE);
    }

    private static MobConfigData load(CompoundTag tag, HolderLookup.Provider registries) {
        MobConfigData d = new MobConfigData();
        CompoundTag t = tag.getCompound("Toggles");
        for (String k : t.getAllKeys()) {
            d.toggles.put(k, Tri.valueOf(t.getString(k)));
        }
        CompoundTag v = tag.getCompound("Values");
        for (String k : v.getAllKeys()) {
            d.values.put(k, v.getDouble(k));
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag t = new CompoundTag();
        toggles.forEach((k, v) -> t.putString(k, v.name()));
        CompoundTag v = new CompoundTag();
        values.forEach(v::putDouble);
        tag.put("Toggles", t);
        tag.put("Values", v);
        return tag;
    }

    private static String key(Scope s, Behavior b) {
        return s.key() + "|" + b.id().getPath();
    }

    public int version() {
        return version;
    }

    public Tri toggle(Scope s, Behavior b) {
        return toggles.getOrDefault(key(s, b), Tri.INHERIT);
    }

    public void setToggle(Scope s, Behavior b, Tri state) {
        if (state == Tri.INHERIT) {
            toggles.remove(key(s, b));
        } else {
            toggles.put(key(s, b), state);
        }
        version++;
        setDirty();
    }

    public Double value(Scope s, Behavior b, String setting) {
        return values.get(key(s, b) + "|" + setting);
    }

    /** value == null resets the setting to inherit. */
    public void setValue(Scope s, Behavior b, String setting, Double value) {
        if (value == null) {
            values.remove(key(s, b) + "|" + setting);
        } else {
            values.put(key(s, b) + "|" + setting, value);
        }
        version++;
        setDirty();
    }

    /** Number of overrides (toggles + values) set at this scope. */
    public int count(Scope s) {
        String p = s.key() + "|";
        int n = 0;
        for (String k : toggles.keySet()) {
            if (k.startsWith(p)) {
                n++;
            }
        }
        for (String k : values.keySet()) {
            if (k.startsWith(p)) {
                n++;
            }
        }
        return n;
    }

    public void clearScope(Scope s) {
        String p = s.key() + "|";
        toggles.keySet().removeIf(k -> k.startsWith(p));
        values.keySet().removeIf(k -> k.startsWith(p));
        version++;
        setDirty();
    }
}
