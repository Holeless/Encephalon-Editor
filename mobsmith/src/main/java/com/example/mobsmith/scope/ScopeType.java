package com.example.mobsmith.scope;

/** Broad to specific. Later entries win over earlier ones when resolving. */
public enum ScopeType {
    WORLD,      // rules that do not belong to a mob
    ALL,        // every mob
    CATEGORY,   // hostile / passive / neutral (MobCategory name)
    GROUP,      // entity type tag or user-made group (TODO)
    MOB_TYPE,   // minecraft:zombie
    INDIVIDUAL  // one entity UUID
}
