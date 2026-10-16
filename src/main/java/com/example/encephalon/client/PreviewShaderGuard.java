package com.example.encephalon.client;

import java.lang.reflect.Field;

import com.example.encephalon.EncephalonMod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;

/**
 * Preview code must never leave a screen shader switched on or off (some mods start one when a boss "exists"). The post effect
 * and its on/off flag are noted before preview code runs and put back after it. Read and written by field, so it does not depend
 * on which accessors this version has.
 * A mod that replaced a shader that was already on cannot be undone (the old chain is closed by then); that is logged.
 */
final class PreviewShaderGuard {
    private static final Field POST = find("postEffect");
    private static final Field ACTIVE = find("effectActive");
    private static boolean warned;

    private PreviewShaderGuard() {
    }

    private static Field find(String name) {
        try {
            Field f = GameRenderer.class.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            EncephalonMod.LOGGER.warn("Mob Editor: GameRenderer.{} not found; preview shaders cannot be undone", name);
            return null;
        }
    }

    static Object[] snapshot() {
        GameRenderer gr = Minecraft.getInstance().gameRenderer;
        if (gr == null || POST == null) {
            return null;
        }
        try {
            return new Object[] {POST.get(gr), ACTIVE == null ? null : ACTIVE.getBoolean(gr)};
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }

    static void restore(Object[] before) {
        GameRenderer gr = Minecraft.getInstance().gameRenderer;
        if (before == null || gr == null) {
            return;
        }
        try {
            Object now = POST.get(gr);
            if (now != before[0]) {
                if (before[0] == null) {
                    gr.shutdownEffect(); // a preview mob turned a shader on
                } else if (!warned) {
                    warned = true;
                    EncephalonMod.LOGGER.warn("Mob Editor preview: a preview mob replaced the active screen shader; it cannot be restored");
                }
            } else if (ACTIVE != null && before[1] != null) {
                ACTIVE.setBoolean(gr, (Boolean) before[1]);
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            // best effort
        }
    }
}
