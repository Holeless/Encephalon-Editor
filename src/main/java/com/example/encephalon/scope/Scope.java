package com.example.encephalon.scope;

public record Scope(ScopeType type, String id) {
    public static final Scope WORLD = new Scope(ScopeType.WORLD, "world");
    public static final Scope ALL = new Scope(ScopeType.ALL, "all");
    public static final Scope VANILLA = new Scope(ScopeType.ORIGIN, "vanilla");
    public static final Scope MODDED = new Scope(ScopeType.ORIGIN, "modded");

    public String key() {
        return type.name() + ":" + id;
    }
}
