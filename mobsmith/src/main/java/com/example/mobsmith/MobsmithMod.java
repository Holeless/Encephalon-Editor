package com.example.mobsmith;

import org.slf4j.Logger;

import com.example.mobsmith.behavior.Behaviors;
import com.mojang.logging.LogUtils;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(MobsmithMod.MOD_ID)
public class MobsmithMod {
    public static final String MOD_ID = "mobsmith";
    public static final Logger LOGGER = LogUtils.getLogger();

    public MobsmithMod(IEventBus modEventBus) {
        LOGGER.info("Mobsmith loaded with {} behavior(s)", Behaviors.all().size());
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
