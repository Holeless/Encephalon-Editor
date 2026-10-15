# Mobsmith (NeoForge 1.21.1)

Edit mob AI: toggle behaviors and tune their settings for all mobs, a category, a group, a mob type, or a single mob.

**Status: shell.** Scopes, behavior registry, config storage, resolver and a basic `/mobai` command exist. No behavior has an effect yet.

## Setup
Copy `gradlew`, `gradlew.bat` and `gradle/wrapper/gradle-wrapper.jar` from another NeoForge project (e.g. Curses), then `./gradlew runClient`.

## Concepts
- **Behavior**: unit of logic (id, icon, archetype, allowed scopes, layer, settings).
- **Scope**: WORLD, ALL, CATEGORY, GROUP, MOB_TYPE, INDIVIDUAL. Most specific wins.
- **Override**: Tri toggle (Inherit/On/Off) or setting value (absolute, or multiplier that stacks).
- **Layer**: 0 Reflex, 1 Tactical, 2 Traversal, 3 Combat, 4 Idle.

## Adding a behavior
1. Register it in `behavior/Behaviors.java`; add lang keys.
2. In its handler/goal, gate with `ConfigResolver.enabled(mob, Behaviors.X)` and read `ConfigResolver.value(...)`.

## TODO
GROUP scopes, resolved-config cache, networking, editor screen, port Curses handlers, Intent/Layer arbitration, profiles, rules builder.

## Editor UI
Press **K** in a world (singleplayer, cheats on) to open the Mob Editor: scope list (World / All / category / mob type, with search and override badges), behavior list for the selected scope, and a detail panel with the Inherit/On/Off state, an origin line ("Set here" / "Inherited from ..."), and number fields (greyed hint = inherited value, Enter applies, values clamp to min/max, R resets).
Multiplayer sync, groups, individual-mob editing (Mob Wand) and the create-world entry point are TODO.
