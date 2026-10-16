package com.example.encephalon.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.example.encephalon.behavior.Behavior;
import com.example.encephalon.scope.Scope;
import com.example.encephalon.scope.ScopeType;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Per-world overrides: toggles and setting values, keyed by scope + behavior (+ setting), and custom drop lists per mob type.
 * Saved as data/encephalon_config.dat.
 */
public class MobConfigData extends SavedData {
    private static final String FILE = "encephalon_config";

    /** One custom drop: item id, chance in percent (0-100), and the amount range when it drops. */
    public record DropEntry(String item, double chance, int min, int max) {
    }

    private final Map<String, Tri> toggles = new ConcurrentHashMap<>();
    private final Map<String, Double> values = new ConcurrentHashMap<>();
    /** Mob type id -> its custom drop list. No entry means "use the default loot table"; an empty list means "drops nothing". */
    private final Map<String, List<DropEntry>> drops = new ConcurrentHashMap<>();
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
        CompoundTag dt = tag.getCompound("Drops");
        for (String k : dt.getAllKeys()) {
            ListTag l = dt.getList(k, Tag.TAG_COMPOUND);
            List<DropEntry> entries = new ArrayList<>();
            for (int i = 0; i < l.size(); i++) {
                CompoundTag c = l.getCompound(i);
                entries.add(new DropEntry(c.getString("Item"), c.getDouble("Chance"), c.getInt("Min"), c.getInt("Max")));
            }
            d.drops.put(k, List.copyOf(entries));
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag t = new CompoundTag();
        toggles.forEach((k, v) -> t.putString(k, v.name()));
        CompoundTag v = new CompoundTag();
        values.forEach(v::putDouble);
        CompoundTag dt = new CompoundTag();
        drops.forEach((mob, list) -> {
            ListTag l = new ListTag();
            for (DropEntry e : list) {
                CompoundTag c = new CompoundTag();
                c.putString("Item", e.item());
                c.putDouble("Chance", e.chance());
                c.putInt("Min", e.min());
                c.putInt("Max", e.max());
                l.add(c);
            }
            dt.put(mob, l);
        });
        tag.put("Toggles", t);
        tag.put("Values", v);
        tag.put("Drops", dt);
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

    /** The custom drop list of a mob type (immutable), or null when it uses its default loot table. */
    public List<DropEntry> drops(String mobId) {
        return drops.get(mobId);
    }

    public void setDrops(String mobId, List<DropEntry> list) {
        drops.put(mobId, List.copyOf(list));
        version++;
        setDirty();
    }

    /** Back to the default loot table. */
    public void clearDrops(String mobId) {
        if (drops.remove(mobId) != null) {
            version++;
            setDirty();
        }
    }

    /** Number of overrides (toggles + values, plus a custom drop list for a mob type) set at this scope. */
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
        if (s.type() == ScopeType.MOB_TYPE && drops.containsKey(s.id())) {
            n++;
        }
        return n;
    }

    public void clearScope(Scope s) {
        String p = s.key() + "|";
        toggles.keySet().removeIf(k -> k.startsWith(p));
        values.keySet().removeIf(k -> k.startsWith(p));
        if (s.type() == ScopeType.MOB_TYPE) {
            drops.remove(s.id());
        }
        version++;
        setDirty();
    }
}
