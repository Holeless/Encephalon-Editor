package com.example.mobsmith.client;

import com.example.mobsmith.MobsmithMod;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/** Client, mod bus: the key that opens the editor. */
@EventBusSubscriber(value = Dist.CLIENT, modid = MobsmithMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class MobsmithClientMod {
    public static final KeyMapping OPEN = new KeyMapping("key.mobsmith.open", InputConstants.KEY_K, "key.categories.mobsmith");

    private MobsmithClientMod() {
    }

    @SubscribeEvent
    public static void onKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN);
    }
}
