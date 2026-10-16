package com.example.encephalon.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.example.encephalon.behavior.Behavior;
import com.example.encephalon.scope.MobGroups;
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
 * Per-world overrides: toggles and setting values, keyed by scope + behavior (+ setting), custom drop lists per mob type,
 * the category (how a mob acts) per mob type, and attack damage per mob type + attack.
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
    /** Mob type id -> how the mob acts (hostile / neutral / passive). No entry means its default one. */
    private final Map<String, String> categories = new ConcurrentHashMap<>();
    /** "mobId|attackId" -> damage points on Normal difficulty. No entry means the default damage. */
    private final Map<String, Double> damages = new ConcurrentHashMap<>();
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
        CompoundTag ct = tag.getCompound("Categories");
        for (String k : ct.getAllKeys()) {
            String c = ct.getString(k);
            if (MobGroups.CATEGORIES.contains(c)) {
                d.categories.put(k, c);
            }
        }
        CompoundTag dm = tag.getCompound("Damage");
        for (String k : dm.getAllKeys()) {
            d.damages.put(k, dm.getDouble(k));
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
        CompoundTag ct = new CompoundTag();
        categories.forEach(ct::putString);
        tag.put("Categories", ct);
        CompoundTag dm = new CompoundTag();
        damages.forEach(dm::putDouble);
        tag.put("Damage", dm);
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

    /** The category set for a mob type ("hostile", "neutral" or "passive"), or null for its default. */
    public String category(String mobId) {
        return categories.get(mobId);
    }

    /** True if any mob type has a category set (lets the game skip the lookup in the common case). */
    public boolean anyCategory() {
        return !categories.isEmpty();
    }

    public void setCategory(String mobId, String category) {
        categories.put(mobId, category);
        version++;
        setDirty();
    }

    /** Back to the default category. */
    public void clearCategory(String mobId) {
        if (categories.remove(mobId) != null) {
            version++;
            setDirty();
        }
    }

    /** The damage set for one attack of a mob type, or null for its default. */
    public Double damage(String mobId, String attack) {
        return damages.get(mobId + "|" + attack);
    }

    public void setDamage(String mobId, String attack, double value) {
        damages.put(mobId + "|" + attack, value);
        version++;
        setDirty();
    }

    /** Back to the default damage of one attack. */
    public void clearDamage(String mobId, String attack) {
        if (damages.remove(mobId + "|" + attack) != null) {
            version++;
            setDirty();
        }
    }

    /** True if any attack of the mob type has a custom damage. */
    public boolean hasDamages(String mobId) {
        String p = mobId + "|";
        for (String k : damages.keySet()) {
            if (k.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /** Back to the default damage of every attack of the mob type. */
    public void clearDamages(String mobId) {
        String p = mobId + "|";
        if (damages.keySet().removeIf(k -> k.startsWith(p))) {
            version++;
            setDirty();
        }
    }

    /** Number of overrides (toggles + values, plus a custom drop list / category / damages for a mob type) set at this scope. */
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
        if (s.type() == ScopeType.MOB_TYPE && categories.containsKey(s.id())) {
            n++;
        }
        if (s.type() == ScopeType.MOB_TYPE) {
            String dp = s.id() + "|";
            for (String k : damages.keySet()) {
                if (k.startsWith(dp)) {
                    n++;
                }
            }
        }
        return n;
    }

    public void clearScope(Scope s) {
        String p = s.key() + "|";
        toggles.keySet().removeIf(k -> k.startsWith(p));
        values.keySet().removeIf(k -> k.startsWith(p));
        if (s.type() == ScopeType.MOB_TYPE) {
            drops.remove(s.id());
            categories.remove(s.id());
            String dp = s.id() + "|";
            damages.keySet().removeIf(k -> k.startsWith(dp));
        }
        version++;
        setDirty();
    }
}
