package com.example.encephalon.client;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.example.encephalon.EncephalonMod;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;

/**
 * Hides boss bars that belong to preview mobs. The server twin of a modded boss registers its bar with the player like any
 * boss would; the bar is not drawn. A bar is a preview bar if it first showed up while the editor was open.
 * Bars that were already on screen before the editor opened are never touched. Render thread only.
 */
@EventBusSubscriber(value = Dist.CLIENT, modid = EncephalonMod.MOD_ID)
public final class PreviewBossBars {
    /** Bars seen while the editor was closed. */
    private static final Set<UUID> SEEN = new HashSet<>();
    /** Bars that appeared while the editor was open; they stay hidden for good. */
    private static final Set<UUID> HIDDEN = new HashSet<>();

    private PreviewBossBars() {
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
            event.setCanceled(true);
            event.setIncrement(0); // no gap where the bar would have been
        } else {
            SEEN.add(id);
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        SEEN.clear();
        HIDDEN.clear();
    }
}
