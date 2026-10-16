package com.example.encephalon.client;

import java.lang.reflect.Field;
import java.util.function.Supplier;

import com.example.encephalon.EncephalonMod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.sounds.MusicManager;
import net.minecraft.world.level.Level;

/**
 * Everything a preview mob's code can reach through the Minecraft singleton is pointed at dummies while that code runs:
 * <ul>
 * <li>Minecraft.level is the preview dummy world (time, weather, sky, entities, scoreboard of the real world are out of reach);</li>
 * <li>Minecraft.getMusicManager() is a spare MusicManager (starting or stopping music hits the spare);</li>
 * <li>PreviewZone's guard is up, so any sound that still starts is dropped (PreviewSilencer);</li>
 * <li>the screen shader state is noted and put back afterwards (PreviewShaderGuard).</li>
 * </ul>
 * Everything is put back in a finally block. Render thread only.
 * What cannot be covered: code that reaches the real world by another road (the player's own level(), a static it cached
 * earlier, a packet to the server), and anything it does to the real SoundManager other than starting a sound.
 */
final class PreviewSandbox {
    private static final Field MUSIC = musicField();
    private static MusicManager spareMusic;

    private PreviewSandbox() {
    }

    private static Field musicField() {
        try {
            Field f = Minecraft.class.getDeclaredField("musicManager");
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            EncephalonMod.LOGGER.warn("Mob Editor: Minecraft.musicManager not found; preview mobs can reach the real music manager");
            return null;
        }
    }

    private static void setMusic(Minecraft mc, MusicManager m) {
        try {
            MUSIC.set(mc, m);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            // leave it
        }
    }

    /** A whole preview pass (create, tick, draw): guard up and shader state restored afterwards. */
    static void run(Runnable r) {
        Object[] shader = PreviewShaderGuard.snapshot();
        try {
            PreviewZone.guarded(r);
        } finally {
            PreviewShaderGuard.restore(shader);
        }
    }

    /** Runs code of a preview mob (constructor, tick, renderer) with the singleton pointed at the dummy world. */
    static <T> T asLevel(Level level, Supplier<T> code) {
        if (!(level instanceof PreviewLevel dummy)) {
            return PreviewZone.guardedGet(code);
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel realLevel = mc.level;
        MusicManager realMusic = mc.getMusicManager();
        boolean music = MUSIC != null;
        try {
            mc.level = dummy;
            if (music) {
                if (spareMusic == null) {
                    spareMusic = new MusicManager(mc);
                }
                setMusic(mc, spareMusic);
            }
            return PreviewZone.guardedGet(code);
        } finally {
            mc.level = realLevel;
            if (music) {
                setMusic(mc, realMusic);
            }
        }
    }

    static void asLevelRun(Level level, Runnable code) {
        asLevel(level, () -> {
            code.run();
            return null;
        });
    }
}
