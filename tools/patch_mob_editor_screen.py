#!/usr/bin/env python3
"""Patches MobEditorScreen.java for the new Attacks panel.

Usage:  python tools/patch_mob_editor_screen.py src/main/java/com/example/encephalon/client/MobEditorScreen.java

Replaces buildDamagePanel, drawDamageEditor, the DamageRow record and the damage box hint. Every step must match exactly once
or nothing is written. A .bak copy is made first. Keeps the file's line endings.
"""
import re
import shutil
import sys
from pathlib import Path

if len(sys.argv) != 2:
    sys.exit(__doc__)
path = Path(sys.argv[1])
raw = path.read_text(encoding="utf-8")
crlf = "\r\n" in raw
s = raw.replace("\r\n", "\n")
if "damageAttacks" in s:
    sys.exit("Already patched (found damageAttacks).")


def once(old, new):
    global s
    n = s.count(old)
    if n != 1:
        sys.exit("Expected exactly one match, found %d for: %r" % (n, old[:70]))
    s = s.replace(old, new)


def between(start, end_regex, new):
    global s
    if s.count(start) != 1:
        sys.exit("Start marker not found exactly once: %r" % start[:70])
    a = s.index(start)
    m = re.compile(end_regex, re.M).search(s, a + len(start))
    if not m:
        sys.exit("End marker not found after: %r" % start[:70])
    s = s[:a] + new + s[m.start():]


once("    private Button damageReset;\n",
     "    private Button damageReset;\n    /** The attacks listed in the open Attacks panel (melee, explosion, other damage, special ones). */\n"
     "    private List<DamageDefaults.Attack> damageAttacks = List.of();\n")

once("    private record DamageRow(DamageDefaults.Attack attack, int y) {",
     "    private record DamageRow(DamageDefaults.Attack attack, int y, List<FormattedCharSequence> info) {")

once("setHint(Component.literal(fmt(attack.def())));",
     'setHint(attack.def() < 0 ? Component.translatable("encephalon.screen.damage_varies") : Component.literal(fmt(attack.def())));')

BUILD = '''    /**
     * Builds the attacks editor: description, a reset button, then one row per attack of the mob. An attack that hurts gets a
     * damage box (name, default, box, R); a special attack that does no damage is listed with a short description instead.
     * The list is worked out from the mob itself on the server (see DamageDefaults), so modded mobs get theirs too.
     */
    private void buildDamagePanel() {
        EntityType<?> type = selectedType();
        String mobId = scope.id();
        String own = data.category(mobId);
        boolean forced = own != null && !"passive".equals(own) && !own.equals(MobGroups.categoryOf(type));
        java.util.concurrent.atomic.AtomicReference<List<DamageDefaults.Attack>> found =
                new java.util.concurrent.atomic.AtomicReference<>(List.of());
        edit(() -> found.set(DamageDefaults.attacksOf(server, type, forced)));
        damageAttacks = found.get();
        damageFallback = DamageDefaults.isFallback(type)
                && damageAttacks.stream().anyMatch(a -> a.kind() == DamageDefaults.Kind.MELEE);
        damageDesc = this.font.split(Component.translatable("encephalon.screen.damage_desc"), rw);
        int y = TOP + 2 + warnH + damageDesc.size() * 10 + 4;
        damageStateY = y;
        y += damageFallback ? 24 : 14;
        damageReset = track(Button.builder(Component.translatable("encephalon.screen.damage_reset"), b -> resetDamage())
                .bounds(rx, y, rw, 20).build());
        damageReset.active = editable && data.hasDamages(scope.id());
        y += 28;
        if (damageAttacks.isEmpty()) {
            y += 14;
        }
        for (DamageDefaults.Attack a : damageAttacks) {
            if (!a.editable()) {
                List<FormattedCharSequence> info = this.font.split(DamageDefaults.describe(a), rw);
                damageRows.add(new DamageRow(a, y, info));
                y += 14 + info.size() * 10 + 6;
                continue;
            }
            damageRows.add(new DamageRow(a, y, List.of()));
            DamageBox box = track(new DamageBox(this.font, rx, y + 20, rw - 24, mobId, a, data.damage(mobId, a.id())));
            box.setEditable(editable);
            Button reset = track(Button.builder(Component.literal("R"), b -> box.reset())
                    .bounds(rx + rw - 20, y + 20, 20, 20)
                    .tooltip(Tooltip.create(Component.translatable("encephalon.screen.reset_tip"))).build());
            reset.active = editable;
            y += 48;
        }
        contentHeight = y + 4 - TOP;
        layoutDetail();
    }

'''
between("    /** Builds the damage editor:", r"^    private void resetDamage\(\) \{", BUILD)

DRAW = '''    /** The grey line under an attack's name: its default, worded for the kind of attack. */
    private static Component attackInfo(DamageDefaults.Attack a) {
        if (a.def() < 0) {
            return Component.translatable("encephalon.screen.damage_info_varies");
        }
        if (a.kind() == DamageDefaults.Kind.EXPLOSION) {
            return Component.translatable("encephalon.screen.damage_info_explosion", fmt(a.def()), fmt(a.def() / 2.0));
        }
        return Component.translatable("encephalon.screen.damage_info", fmt(a.def()), fmt(a.def() / 2.0));
    }

    private void drawDamageEditor(GuiGraphics g) {
        for (int i = 0; i < damageDesc.size(); i++) {
            g.drawString(this.font, damageDesc.get(i), rx, TOP + 2 + warnH + i * 10 - scroll, 0xC8C8C8, true);
        }
        boolean custom = data.hasDamages(scope.id());
        Component state = Component.translatable(custom ? "encephalon.screen.damage_state_custom" : "encephalon.screen.damage_state_default");
        g.drawString(this.font, fit(state.getString(), rw), rx, damageStateY - scroll, custom ? 0xFFAA00 : 0xA0A0A0, true);
        if (damageFallback) {
            Component note = Component.translatable("encephalon.screen.damage_fallback",
                    Component.translatable("encephalon.size." + DamageDefaults.sizeClass(selectedType())));
            g.drawString(this.font, fit(note.getString(), rw), rx, damageStateY + 10 - scroll, 0x808080, true);
        }
        if (damageAttacks.isEmpty()) {
            int ny = damageStateY + (damageFallback ? 24 : 14) + 28 - scroll;
            g.drawString(this.font, Component.translatable("encephalon.screen.attacks_none"), rx, ny, 0x808080, true);
        }
        for (DamageRow row : damageRows) {
            DamageDefaults.Attack a = row.attack();
            int y = row.y() - scroll;
            Component name = DamageDefaults.nameOf(a);
            if (!a.editable()) {
                // A special attack that does no damage: its name, then what it does.
                g.drawString(this.font, name, rx, y, 0xFFC878, true);
                for (int i = 0; i < row.info().size(); i++) {
                    g.drawString(this.font, row.info().get(i), rx, y + 12 + i * 10, 0x909090, true);
                }
                continue;
            }
            boolean set = data.damage(scope.id(), a.id()) != null;
            g.drawString(this.font, name, rx, y, set ? 0xFFFFFF : 0xA0A0A0, true);
            g.drawString(this.font, fit(attackInfo(a).getString(), rw), rx, y + 10, 0x808080, true);
        }
    }

'''
between("    private void drawDamageEditor(GuiGraphics g) {", r"^    // -+ 3D preview helpers", DRAW)

if crlf:
    s = s.replace("\n", "\r\n")
shutil.copyfile(path, str(path) + ".bak")
path.write_text(s, encoding="utf-8", newline="")
print("Patched", path, "(backup: %s.bak)" % path)
