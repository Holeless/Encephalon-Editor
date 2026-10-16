#!/usr/bin/env python3
"""Patches MobEditorScreen.java in place. Run from the repo root:  python tools/apply_editor_fix.py
Every edit must match exactly once, otherwise nothing is written (a .bak copy is made before writing)."""
import shutil
import sys

PATH = sys.argv[1] if len(sys.argv) > 1 else "src/main/java/com/example/encephalon/client/MobEditorScreen.java"

EDITS = []


def edit(old, new):
    EDITS.append((old, new))


# 1. constants
edit("    private static final int MAX_STABLE_WAIT = 60;",
"""    private static final int MAX_STABLE_WAIT = 60;
    // The shown mob is only revealed once its fitted size / position has held still this many frames (or after FIT_MAX_WAIT_FRAMES),
    // so it appears in place instead of sliding in from the edge while the fit is still settling.
    private static final int FIT_STEADY_FRAMES = 15;
    private static final int FIT_MAX_WAIT_FRAMES = 180;
    // After this many measured vertices only every 8th one is measured (big models cost a lot per hidden draw otherwise).
    private static final int MB_FULL_SAMPLES = 4000;""")

# 2. Preview fields
edit("        boolean longX;",
"""        boolean longX;
        // Reveal gate (see FIT_STEADY_FRAMES).
        float[] lastFit;
        int fitSteady;
        int fitWait;
        boolean fitReady;
        /** Vertex counter for the extent probe once it has enough samples (see MB_FULL_SAMPLES). */
        int skip;""")

# 3. visible only once the fit has settled (modded mobs)
edit("        boolean visible = revealable(p);",
     "        boolean visible = revealable(p) && (p.fitReady || isVanilla(p.type));")

# 4. the gate itself, right before the easing
edit("        if (p.sScale < 0F || !visible) { // while it is not shown yet there is nothing to ease",
"""        // Reveal gate for modded mobs: keep it hidden (it keeps warming up) until the fit has stopped moving.
        if (!p.fitReady && !isVanilla(p.type) && revealable(p)) {
            float[] cur = {tScale, tCenterY, tPivX, tPivZ, tYaw};
            float[] last = p.lastFit;
            boolean same = last != null
                    && Math.abs(cur[0] - last[0]) <= 0.01F * Math.max(cur[0], 1F)
                    && Math.abs(cur[1] - last[1]) * cur[0] <= 1.5F
                    && Math.abs(cur[2] - last[2]) * cur[0] <= 1.5F
                    && Math.abs(cur[3] - last[3]) * cur[0] <= 1.5F
                    && Math.abs(cur[4] - last[4]) <= 0.01F;
            if (same) {
                p.fitSteady++;
            } else {
                p.lastFit = cur;
                p.fitSteady = 0;
            }
            if (p.fitSteady >= FIT_STEADY_FRAMES || ++p.fitWait >= FIT_MAX_WAIT_FRAMES) {
                p.fitReady = true;
                visible = true;
            }
        }
        if (p.sScale < 0F || !visible) { // while it is not shown yet there is nothing to ease""")

# 5. no twin for mods that never need one
edit("        if (EncephalonConfig.isQuietListed(t)) {",
     "        if (EncephalonConfig.isQuietListed(t) || EncephalonConfig.isNoTwinMod(t)) {")

# 6. cheaper extent probe
edit("                inv.transformPosition(x, y, z, tmp);",
"""                if (p.samples < MB_FULL_SAMPLES || (++p.skip & 7) == 0) {
                    inv.transformPosition(x, y, z, tmp);""")
edit("                p.measure(tmp.x, tmp.y, tmp.z);",
"""                    p.measure(tmp.x, tmp.y, tmp.z);
                }""")

# 7. timing of the throwaway instance (shows up in latest.log when slow)
edit('            c = Void.class; // "unknown", so a type that can\'t be made is not tried again every frame',
     '            c = Void.class; // "unknown", so a type that can\'t be made is not tried again every frame\n'
     '            long t0 = System.nanoTime();')
edit("            CLASS_OF.put(t, c);",
     '            slow("making a throwaway instance (to learn its class)", t, t0);\n            CLASS_OF.put(t, c);')

with open(PATH, "r", newline="", encoding="utf-8") as f:
    text = f.read()
nl = "\r\n" if "\r\n" in text else "\n"

if "FIT_STEADY_FRAMES" in text:
    sys.exit("Already patched, nothing to do.")

out = text
for i, (old, new) in enumerate(EDITS, 1):
    old = old.replace("\n", nl)
    new = new.replace("\n", nl)
    n = out.count(old)
    if n != 1:
        sys.exit("Edit %d matched %d times (expected 1); nothing was changed. Anchor: %s" % (i, n, old.strip()[:70]))
    out = out.replace(old, new)

shutil.copyfile(PATH, PATH + ".bak")
with open(PATH, "w", newline="", encoding="utf-8") as f:
    f.write(out)
print("Patched %s (%d edits). Backup: %s.bak" % (PATH, len(EDITS), PATH))
