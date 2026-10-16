package com.example.encephalon.client;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.example.encephalon.EncephalonMod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;

/**
 * Hides boss bars that belong to preview mobs. The server twin of a modded boss registers its bar with the player like any
 * boss would. A bar is a preview bar if it first showed up while the editor was open.
 * Bars that were already on screen before the editor opened are never touched. Render thread only.
 * <p>
 * Not drawing the bar is not enough: a boss bar also carries flags that the rest of the game reads (darken the screen, thicken
 * the fog, play boss music), and mods read them too. So the flags of every preview bar are switched off, every tick, for good
 * (the server can send them again at any time, and a mod may not remove the bar when its twin is discarded).
 */
@EventBusSubscriber(value = Dist.CLIENT, modid = EncephalonMod.MOD_ID)
public final class PreviewBossBars {
    /** Bars seen while the editor was closed. */
    private static final Set<UUID> SEEN = new HashSet<>();
    /** Bars that appeared while the editor was open; they stay hidden for good. */
    private static final Set<UUID> HIDDEN = new HashSet<>();
    private static final Field EVENTS = eventsField();

    private PreviewBossBars() {
    }

    private static Field eventsField() {
        try {
            Field f = BossHealthOverlay.class.getDeclaredField("events");
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            EncephalonMod.LOGGER.warn("Mob Editor: boss bar list not found; preview boss bars are only hidden, not disarmed");
            return null;
        }
    }

    private static void disarm(LerpingBossEvent bar) {
        bar.setDarkenScreen(false);
        bar.setPlayBossMusic(false);
        bar.setCreateWorldFog(false);
    }

    @SubscribeEvent
    public static void onBar(CustomizeGuiOverlayEvent.BossEventProgress event) {
        UUID id = event.getBossEvent().getId();
        boolean hide = HIDDEN.contains(id);
        if (!hide && PreviewZone.zone != null && !SEEN.contains(id)) {
            HIDDEN.add(id);
            hide = true;
        }
        if (hide) {
            disarm(event.getBossEvent());
            event.setCanceled(true);
            event.setIncrement(0); // no gap where the bar would have been
        } else {
            SEEN.add(id);
        }
    }

    /** Also covers the frames in which the GUI is hidden (F1), when no bar is drawn and so the event above never fires. */
    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (EVENTS == null || mc.gui == null) {
            return;
        }
        try {
            Object map = EVENTS.get(mc.gui.getBossOverlay());
            if (!(map instanceof Map<?, ?> bars)) {
                return;
            }
            for (Map.Entry<?, ?> entry : bars.entrySet()) {
                if (!(entry.getKey() instanceof UUID id) || !(entry.getValue() instanceof LerpingBossEvent bar)) {
                    continue;
                }
                if (PreviewZone.zone == null && !HIDDEN.contains(id)) {
                    SEEN.add(id);
                    continue;
                }
                if (!HIDDEN.contains(id) && !SEEN.contains(id)) {
                    HIDDEN.add(id);
                }
                if (HIDDEN.contains(id)) {
                    disarm(bar);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            // best effort
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        SEEN.clear();
        HIDDEN.clear();
    }
}
