package com.example.encephalon.client;

import com.example.encephalon.EncephalonMod;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;

/**
 * Client side of the preview silencing. A sound is dropped when
 * (1) it starts while preview code is running (PreviewZone.guarded: creating, ticking or drawing a preview mob), whatever its
 *     source, position or attenuation: nothing a preview mob does may make noise;
 * (2) it starts inside the preview column;
 * (3) while the editor is open, it is music (any attenuation), or a non-positional record: this is how boss themes are
 *     started, including by a server twin through a mod's own packet. Vanilla's background music just waits until the editor closes.
 */
@EventBusSubscriber(value = Dist.CLIENT, modid = EncephalonMod.MOD_ID)
public final class PreviewSilencer {
    private PreviewSilencer() {
    }

    @SubscribeEvent
    public static void onSound(PlaySoundEvent event) {
        SoundInstance s = event.getSound();
        if (s == null) {
            return;
        }
        if (PreviewZone.guarding()) {
            event.setSound(null); // null cancels it
            return;
        }
        if (PreviewZone.zone == null) {
            return;
        }
        if (PreviewZone.contains(new Vec3(s.getX(), s.getY(), s.getZ()))) {
            event.setSound(null);
            return;
        }
        SoundSource src = s.getSource();
        if (src == SoundSource.MUSIC
                || (src == SoundSource.RECORDS && s.getAttenuation() == SoundInstance.Attenuation.NONE)) {
            event.setSound(null);
        }
    }
}
