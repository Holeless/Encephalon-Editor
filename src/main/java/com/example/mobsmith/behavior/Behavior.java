package com.example.mobsmith.behavior;

import java.util.List;
import java.util.Set;

import com.example.mobsmith.scope.ScopeType;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/** One unit of logic: metadata only. The effect lives in a handler or goal that asks the resolver. */
public record Behavior(ResourceLocation id, Item icon, Archetype archetype, Set<ScopeType> scopes,
                       int layer, List<Setting> settings) {
    public Component name() {
        return Component.translatable("behavior." + id.getNamespace() + "." + id.getPath() + ".name");
    }

    public Component description() {
        return Component.translatable("behavior." + id.getNamespace() + "." + id.getPath() + ".description");
    }

    public Setting setting(String key) {
        for (Setting s : settings) {
            if (s.key().equals(key)) {
                return s;
            }
        }
        return null;
    }
}
