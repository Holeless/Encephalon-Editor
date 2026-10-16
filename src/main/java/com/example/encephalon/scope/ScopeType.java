package com.example.encephalon.scope;

/** Broad to specific. Later entries win over earlier ones when resolving. */
public enum ScopeType {
    WORLD,      // rules that do not belong to a mob
    ALL,        // every mob, vanilla and modded (not shown in the editor tree, but still resolved)
    ORIGIN,     // "vanilla" (namespace minecraft) or "modded" (every other namespace)
    MOD,        // every mob added by one mod; id = mod id
    CATEGORY,   // vanilla hostile / neutral / passive
    MOD_CATEGORY, // hostile / neutral / passive within one mod; id = "modid/category"
    GROUP,      // entity type tag or user-made group (TODO)
    MOB_TYPE,   // minecraft:zombie
    INDIVIDUAL  // one entity UUID
}
