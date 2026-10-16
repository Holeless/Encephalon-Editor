package com.example.encephalon.client;

import com.example.encephalon.EncephalonMod;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;

/** Client side of the preview silencing: drops any sound instance (one-shot or looping) that starts inside the preview column. */
@EventBusSubscriber(value = Dist.CLIENT, modid = EncephalonMod.MOD_ID)
public final class PreviewSilencer {
    private PreviewSilencer() {
    }

    @SubscribeEvent
    public static void onSound(PlaySoundEvent event) {
        SoundInstance s = event.getSound();
        if (s != null && PreviewZone.contains(new Vec3(s.getX(), s.getY(), s.getZ()))) {
            event.setSound(null); // null cancels it
        }
    }
}
