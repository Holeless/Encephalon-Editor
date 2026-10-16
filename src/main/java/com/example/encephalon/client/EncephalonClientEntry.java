package com.example.encephalon.client;

import com.example.encephalon.EncephalonMod;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** Client entry point: adds Mods > Encephalon > Config. */
@Mod(value = EncephalonMod.MOD_ID, dist = Dist.CLIENT)
public class EncephalonClientEntry {
    public EncephalonClientEntry(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
