#!/usr/bin/env python3
"""Patches MobEditorScreen.java in place. Run from the repo root:  python tools/apply_heavy_mob_fix.py
1. Heavy-mob guard: a mob whose warm-up / tick / draw is slow gets a short, cheap warm-up instead of ~70 full ones.
2. Slide-in fix: a modded mob is revealed only once its fit has stopped moving (skipped if apply_editor_fix.py already did this).
Every edit must match exactly once, otherwise nothing is written (a .bak copy is made first)."""
import shutil
import sys

PATH = sys.argv[1] if len(sys.argv) > 1 else "src/main/java/com/example/encephalon/client/MobEditorScreen.java"

with open(PATH, "r", newline="", encoding="utf-8") as f:
    text = f.read()
nl = "\r\n" if "\r\n" in text else "\n"

if "HEAVY_FRAME_NANOS" in text:
    sys.exit("Already patched, nothing to do.")

has_gate = "FIT_STEADY_FRAMES" in text
EDITS = []


def edit(old, new):
    EDITS.append((old, new))


# ---- constants
edit("    private static final int MAX_STABLE_WAIT = 60;",
"""    private static final int MAX_STABLE_WAIT = 60;
    // Heavy-mob guard. A warm-up batch (ticks + one hidden draw) slower than HEAVY_FRAME_NANOS marks the mob heavy: it then steps
    // one tick per frame and is declared warmed up after HEAVY_BATCHES batches instead of the usual ~70 ticks. One entity tick
    // slower than HUGE_TICK_NANOS stops ticking it at once. HEAVY_DRAW_NANOS: a shown mob whose draw is this slow, 5 times, is not ticked.
    private static final long HEAVY_FRAME_NANOS = 30_000_000L;
    private static final int HEAVY_BATCHES = 8;
    private static final long HUGE_TICK_NANOS = 40_000_000L;
    private static final long HEAVY_DRAW_NANOS = 50_000_000L;""" + ("" if has_gate else """
    private static final int REVEAL_STEADY_FRAMES = 15;
    private static final int REVEAL_MAX_WAIT_FRAMES = 180;"""))

# ---- Preview fields
edit("        boolean longX;",
"""        boolean longX;
        /** Warm-up batches were slow (see HEAVY_FRAME_NANOS). */
        boolean heavy;
        int heavyBatches;
        int slowDraws;""" + ("" if has_gate else """
        float[] lastFit;
        int fitSteady;
        int fitWait;
        boolean fitReady;"""))

# ---- warm-up: first batch is a single tick (a cheap probe), heavy mobs stay at one tick per batch and finish early
edit("        int steps = System.nanoTime() > warmDeadline ? 1 : FAST_STEPS;",
"""        long ffStart = System.nanoTime();
        int steps = (p.heavy || p.warmTicks == 0 || System.nanoTime() > warmDeadline) ? 1 : FAST_STEPS;""")
edit("        p.trackStable(steps);",
"""        p.trackStable(steps);
        if (System.nanoTime() - ffStart > HEAVY_FRAME_NANOS && !p.heavy) {
            p.heavy = true;
            EncephalonMod.LOGGER.warn("Mob Editor preview: {} is heavy (warm-up batch took {} ms); using a short warm-up",
                    BuiltInRegistries.ENTITY_TYPE.getKey(p.type), (System.nanoTime() - ffStart) / 1_000_000L);
        }
        if (p.heavy && !p.stable && ++p.heavyBatches >= HEAVY_BATCHES) {
            p.stable = true; // stops measuring too: it is fitted by its hitbox / what was measured so far
            p.warmTicks = Math.max(p.warmTicks, WARMUP_TICKS);
        }""")

# ---- real-time catch-up: one tick per frame for heavy mobs
edit("            if (steps++ < 4) {", "            if (steps++ < (p.heavy ? 1 : 4)) {")

# ---- one huge tick stops ticking at once
edit("""                if (System.nanoTime() - tickStart > SLOW_TICK_NANOS) {
                    if (++p.slowTicks >= SLOW_TICK_LIMIT) {""",
"""                long tickTook = System.nanoTime() - tickStart;
                if (tickTook > HUGE_TICK_NANOS) {
                    p.slowTicks = SLOW_TICK_LIMIT - 1;
                }
                if (tickTook > SLOW_TICK_NANOS) {
                    if (++p.slowTicks >= SLOW_TICK_LIMIT) {""")

# ---- a shown mob whose draw is slow is not ticked any more (the tick often costs as much as the draw)
edit("            RenderSystem.runAsFancy(() -> drawModels(g, p, true, partial, drawExtras, realPartial, pose, dispatcher));",
"""            long drawStart = System.nanoTime();
            RenderSystem.runAsFancy(() -> drawModels(g, p, true, partial, drawExtras, realPartial, pose, dispatcher));
            if (System.nanoTime() - drawStart > HEAVY_DRAW_NANOS) {
                if (++p.slowDraws >= 5 && !p.tickFailed) {
                    p.tickFailed = true;
                    EncephalonMod.LOGGER.warn("Mob Editor preview: {} draws slowly; it is no longer ticked",
                            BuiltInRegistries.ENTITY_TYPE.getKey(p.type));
                }
            } else if (p.slowDraws > 0) {
                p.slowDraws--;
            }""")

# ---- slide-in fix: reveal only once the fit has stopped moving (modded mobs)
if not has_gate:
    edit("        boolean visible = revealable(p);",
         "        boolean visible = revealable(p) && (p.fitReady || isVanilla(p.type));")
    edit("        if (p.sScale < 0F || !visible) { // while it is not shown yet there is nothing to ease",
"""        // Reveal gate for modded mobs: hidden (still warming up) until the fit has stopped moving, so it appears in place.
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
            if (p.fitSteady >= REVEAL_STEADY_FRAMES || ++p.fitWait >= REVEAL_MAX_WAIT_FRAMES || p.heavy) {
                p.fitReady = true;
                visible = true;
            }
        }
        if (p.sScale < 0F || !visible) { // while it is not shown yet there is nothing to ease""")

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
print("Patched %s (%d edits%s). Backup: %s.bak" % (PATH, len(EDITS), ", slide-in gate already present" if has_gate else "", PATH))
