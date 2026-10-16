package com.example.encephalon;

import org.slf4j.Logger;

import com.example.encephalon.behavior.Behaviors;
import com.example.encephalon.config.EncephalonConfig;
import com.mojang.logging.LogUtils;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

@Mod(EncephalonMod.MOD_ID)
public class EncephalonMod {
    public static final String MOD_ID = "encephalon";
    public static final Logger LOGGER = LogUtils.getLogger();

    public EncephalonMod(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.CLIENT, EncephalonConfig.SPEC);
        LOGGER.info("Encephalon loaded with {} behavior(s)", Behaviors.all().size());
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
