package com.example.encephalon.config;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Per-world hitbox overrides, one entry per mob type. Kept in its own file (data/encephalon_hitboxes.dat) so it does not
 * touch MobConfigData. wMul / hMul scale the width / height; fit = true means "use fitW / fitH (measured from the model)
 * instead of the type's default size" before the multipliers apply. The fitted size is stored as plain numbers, so a fitted
 * hitbox costs nothing per tick.
 */
public class HitboxData extends SavedData {
    private static final String FILE = "encephalon_hitboxes";

    public record Hitbox(double wMul, double hMul, boolean fit, double fitW, double fitH) {
        public static final Hitbox NONE = new Hitbox(1.0, 1.0, false, 0.0, 0.0);

        public boolean isDefault() {
            return !fit && wMul == 1.0 && hMul == 1.0;
        }

        /** {width, height} in blocks, given the type's default size. */
        public float[] size(float defW, float defH) {
            double w = (fit ? fitW : defW) * wMul;
            double h = (fit ? fitH : defH) * hMul;
            return new float[] {(float) Math.max(0.05, Math.min(64.0, w)), (float) Math.max(0.05, Math.min(64.0, h))};
        }
    }

    private final Map<String, Hitbox> boxes = new ConcurrentHashMap<>();
    private volatile int version;

    public static HitboxData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(HitboxData::new, HitboxData::load, DataFixTypes.LEVEL), FILE);
    }

    private static HitboxData load(CompoundTag tag, HolderLookup.Provider registries) {
        HitboxData d = new HitboxData();
        CompoundTag t = tag.getCompound("Boxes");
        for (String k : t.getAllKeys()) {
            CompoundTag c = t.getCompound(k);
            d.boxes.put(k, new Hitbox(c.getDouble("WMul"), c.getDouble("HMul"), c.getBoolean("Fit"),
                    c.getDouble("FitW"), c.getDouble("FitH")));
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag t = new CompoundTag();
        boxes.forEach((k, h) -> {
            CompoundTag c = new CompoundTag();
            c.putDouble("WMul", h.wMul());
            c.putDouble("HMul", h.hMul());
            c.putBoolean("Fit", h.fit());
            c.putDouble("FitW", h.fitW());
            c.putDouble("FitH", h.fitH());
            t.put(k, c);
        });
        tag.put("Boxes", t);
        return tag;
    }

    public int version() {
        return version;
    }

    public boolean any() {
        return !boxes.isEmpty();
    }

    /** The override of a mob type, or Hitbox.NONE. */
    public Hitbox hitbox(String mobId) {
        return boxes.getOrDefault(mobId, Hitbox.NONE);
    }

    public boolean has(String mobId) {
        return boxes.containsKey(mobId);
    }

    public void set(String mobId, Hitbox h) {
        if (h.isDefault()) {
            boxes.remove(mobId);
        } else {
            boxes.put(mobId, h);
        }
        version++;
        setDirty();
    }

    public void clear(String mobId) {
        if (boxes.remove(mobId) != null) {
            version++;
            setDirty();
        }
    }
}
