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
 * Client side of the preview silencing: drops any sound instance (one-shot or looping) that starts inside the preview column,
 * and, while the editor is open, any music that starts (a non-positional music / record instance), which is how boss themes
 * are played. Vanilla's own background music just waits until the editor is closed.
 */
@EventBusSubscriber(value = Dist.CLIENT, modid = EncephalonMod.MOD_ID)
public final class PreviewSilencer {
    private PreviewSilencer() {
    }

    @SubscribeEvent
    public static void onSound(PlaySoundEvent event) {
        SoundInstance s = event.getSound();
        if (s == null || PreviewZone.zone == null) {
            return;
        }
        if (PreviewZone.contains(new Vec3(s.getX(), s.getY(), s.getZ()))) {
            event.setSound(null); // null cancels it
            return;
        }
        // Boss music is started by the boss itself (even a preview copy of it) and is not played at the boss's position. Some
        // mods use vanilla's own music classes for it, so nothing that starts as music while the editor is open is let through.
        SoundSource src = s.getSource();
        if (s.getAttenuation() == SoundInstance.Attenuation.NONE
                && (src == SoundSource.MUSIC || src == SoundSource.RECORDS)) {
            event.setSound(null);
        }
    }
}
