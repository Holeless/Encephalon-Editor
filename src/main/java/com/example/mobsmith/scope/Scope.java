package com.example.mobsmith.scope;

public record Scope(ScopeType type, String id) {
    public static final Scope WORLD = new Scope(ScopeType.WORLD, "world");
    public static final Scope ALL = new Scope(ScopeType.ALL, "all");

    public String key() {
        return type.name() + ":" + id;
    }
}
