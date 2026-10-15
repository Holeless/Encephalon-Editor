package com.example.mobsmith.client;

import com.example.mobsmith.MobsmithMod;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Client, game bus: opens the editor when the key is pressed in a world. */
@EventBusSubscriber(value = Dist.CLIENT, modid = MobsmithMod.MOD_ID)
public final class MobsmithClientEvents {
    private MobsmithClientEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        while (MobsmithClientMod.OPEN.consumeClick()) {
            if (mc.player != null && mc.screen == null) {
                mc.setScreen(new MobEditorScreen());
            }
        }
    }
}
