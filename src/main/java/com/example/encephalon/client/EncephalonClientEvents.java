package com.example.encephalon.client;

import com.example.encephalon.EncephalonMod;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Client, game bus: opens the editor when the key is pressed in a world. */
@EventBusSubscriber(value = Dist.CLIENT, modid = EncephalonMod.MOD_ID)
public final class EncephalonClientEvents {
    private EncephalonClientEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        while (EncephalonClientMod.OPEN.consumeClick()) {
            if (mc.player != null && mc.screen == null) {
                mc.setScreen(new MobEditorScreen());
            }
        }
    }
}
