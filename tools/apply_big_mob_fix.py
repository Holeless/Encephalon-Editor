#!/usr/bin/env python3
"""Patches MobEditorScreen.java in place. Run from the repo root:  python tools/apply_big_mob_fix.py
(Needs the EncephalonConfig.java and en_us.json from the same zip: they add hoverPrewarm / previewBreakerMs / the preview_off text.)

What it changes (every edit must match exactly once, otherwise nothing is written; a .bak copy is made first):
 1. Hover pre-warm is off unless config hoverPrewarm=true. Before, every modded row the mouse crossed built a mob, a blocking
    server twin and ~70 simulated ticks with hidden draws.
 2. noTwin() also honours EncephalonConfig.isNoTwinMod (the built-in "naturalist" entry and the noTwinMods config list were
    defined but never consulted, so Naturalist mobs such as the giraffe still got a twin).
 3. Heavy-mob guard (same constant names as apply_heavy_mob_fix.py, so that script then reports "Already patched"), now also
    triggered by vertices per draw (a big model), not only by the clock, with tighter limits.
    If apply_heavy_mob_fix.py was already run, its limits are just tightened and the vertex trigger is added.
 4. Circuit breaker: a preview draw slower than previewBreakerMs (default 120) on 2 frames switches that mob's preview off for
    the editor session and says so in the panel, instead of freezing the game. Covers the shown mob and hidden warm-up draws.
It does NOT touch the slide-in gate or the probe thinning of apply_editor_fix.py; run that one too if you want them."""
import re
import shutil
import sys

PATH = sys.argv[1] if len(sys.argv) > 1 else "src/main/java/com/example/encephalon/client/MobEditorScreen.java"

with open(PATH, "r", newline="", encoding="utf-8") as f:
    text = f.read()
nl = "\r\n" if "\r\n" in text else "\n"

if "BREAKER_STRIKES" in text:
    sys.exit("Already patched, nothing to do.")

has_heavy = "HEAVY_FRAME_NANOS" in text
EDITS = []


def edit(old, new):
    EDITS.append((old, new))


# ---- constants
heavy_consts = """
    // Heavy-mob guard. A warm-up batch (ticks + one hidden draw) slower than HEAVY_FRAME_NANOS, or a hidden draw of more than
    // HEAVY_VERTICES vertices, marks the mob heavy: it then steps one tick per frame and is declared warmed up after
    // HEAVY_BATCHES batches instead of the usual ~70 ticks. One entity tick slower than HUGE_TICK_NANOS stops ticking it at once.
    // HEAVY_DRAW_NANOS: a shown mob whose draw is this slow, 5 times, is not ticked.
    private static final long HEAVY_FRAME_NANOS = 12_000_000L;
    private static final int HEAVY_BATCHES = 8;
    private static final long HUGE_TICK_NANOS = 25_000_000L;
    private static final long HEAVY_DRAW_NANOS = 30_000_000L;""" if not has_heavy else """
    // A hidden draw of more than HEAVY_VERTICES vertices marks the mob heavy (see the heavy-mob guard below)."""
edit("    private static final int MAX_STABLE_WAIT = 60;",
     "    private static final int MAX_STABLE_WAIT = 60;" + heavy_consts + """
    private static final int HEAVY_VERTICES = 6000;
    // Circuit breaker (see breaker()): this many slow draws (EncephalonConfig.breakerNanos) switch a mob's preview off.
    private static final int BREAKER_STRIKES = 2;""")

# ---- Preview fields
edit("        boolean longX;",
     "        boolean longX;" + ("" if has_heavy else """
        /** Warm-up batches were slow, or the model is big (see HEAVY_FRAME_NANOS / HEAVY_VERTICES). */
        boolean heavy;
        int heavyBatches;
        int slowDraws;""") + """
        /** Vertices seen by the extent probe since fastForward last reset it (counted from the very first draw). */
        int drawVerts;
        /** Slow draws seen by the circuit breaker (decays on fast frames). */
        int hugeFrames;""")

# ---- vertex counter: counted before the bind-pose early return, so it works from the first warm-up draw
edit("        void measure(float wx, float wy, float wz) {",
     """        void measure(float wx, float wy, float wz) {
            drawVerts++;""")

# ---- warm-up
edit("        boolean ok = hiddenRender(g, p, true, 0F, raw, realPartial);",
     """        p.drawVerts = 0;
        boolean ok = hiddenRender(g, p, true, 0F, raw, realPartial);""")

if not has_heavy:
    edit("        int steps = System.nanoTime() > warmDeadline ? 1 : FAST_STEPS;",
         """        long ffStart = System.nanoTime();
        int steps = (p.heavy || p.warmTicks == 0 || System.nanoTime() > warmDeadline) ? 1 : FAST_STEPS;""")
    edit("        p.trackStable(steps);",
         """        p.trackStable(steps);
        if (!p.heavy && (System.nanoTime() - ffStart > HEAVY_FRAME_NANOS || p.drawVerts > HEAVY_VERTICES)) {
            p.heavy = true;
            EncephalonMod.LOGGER.warn("Mob Editor preview: {} is heavy ({} ms for one warm-up batch, {} vertices per draw); using a short warm-up",
                    BuiltInRegistries.ENTITY_TYPE.getKey(p.type), (System.nanoTime() - ffStart) / 1_000_000L, p.drawVerts);
        }
        if (p.heavy && !p.stable && ++p.heavyBatches >= HEAVY_BATCHES) {
            p.stable = true; // stops measuring too: it is fitted by its hitbox / what was measured so far
            p.warmTicks = Math.max(p.warmTicks, WARMUP_TICKS);
        }""")
    edit("            if (steps++ < 4) {", "            if (steps++ < (p.heavy ? 1 : 4)) {")
    edit("""                if (System.nanoTime() - tickStart > SLOW_TICK_NANOS) {
                    if (++p.slowTicks >= SLOW_TICK_LIMIT) {""",
         """                long tickTook = System.nanoTime() - tickStart;
                if (tickTook > HUGE_TICK_NANOS) {
                    p.slowTicks = SLOW_TICK_LIMIT - 1;
                }
                if (tickTook > SLOW_TICK_NANOS) {
                    if (++p.slowTicks >= SLOW_TICK_LIMIT) {""")
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
else:
    # apply_heavy_mob_fix.py already ran: add the vertex trigger to its check
    edit("        if (System.nanoTime() - ffStart > HEAVY_FRAME_NANOS && !p.heavy) {",
         "        if (!p.heavy && (System.nanoTime() - ffStart > HEAVY_FRAME_NANOS || p.drawVerts > HEAVY_VERTICES)) {")

# ---- twin: the mod list (built-in "naturalist" + config noTwinMods) was never consulted
edit("        return !EncephalonConfig.bossTwins() && quiet(t);",
     """        if (EncephalonConfig.isNoTwinMod(t)) {
            return true;
        }
        return !EncephalonConfig.bossTwins() && quiet(t);""")

# ---- hover pre-warm only when asked for
edit("""    void hoverPrewarm(EntityType<?> t) {
        hoverType = t;""",
     """    void hoverPrewarm(EntityType<?> t) {
        if (!EncephalonConfig.hoverPrewarm()) {
            return;
        }
        hoverType = t;""")

# ---- circuit breaker on hidden draws
edit("""        slow("hidden warm-up render", p.type, t0);
        return done;""",
     """        slow("hidden warm-up render", p.type, t0);
        if (breaker(p, t0, "hidden warm-up render")) {
            return false;
        }
        return done;""")

# ---- circuit breaker on the shown mob + the panel message
edit("""    private void drawActive(GuiGraphics g, Preview p, float realPartial) {
        long t0 = System.nanoTime();
        drawActiveImpl(g, p, realPartial);
        slow("drawing shown mob", p.type, t0);
    }""",
     """    private void drawActive(GuiGraphics g, Preview p, float realPartial) {
        long t0 = System.nanoTime();
        drawActiveImpl(g, p, realPartial);
        slow("drawing shown mob", p.type, t0);
        breaker(p, t0, "drawing shown mob");
    }

    /**
     * Circuit breaker. A draw slower than EncephalonConfig.breakerNanos() is a strike; BREAKER_STRIKES strikes (they decay on
     * fast frames, so a one-off hitch such as a first texture load does not count) switch the preview of this mob off for the
     * editor session: it goes in badTypes, is disposed, and the panel says so. Returns true when it tripped.
     */
    private boolean breaker(Preview p, long startNanos, String what) {
        long limit = EncephalonConfig.breakerNanos();
        long took = System.nanoTime() - startNanos;
        if (limit <= 0L || took <= limit) {
            if (p.hugeFrames > 0 && (frame & 63L) == 0L) {
                p.hugeFrames--;
            }
            return false;
        }
        if (++p.hugeFrames < BREAKER_STRIKES) {
            return false;
        }
        EncephalonMod.LOGGER.warn("Mob Editor preview: {} took {} ms ({} vertices per draw, {} warm-up ticks) for {}; preview switched off for this mob",
                what, took / 1_000_000L, p.drawVerts, p.warmTicks, BuiltInRegistries.ENTITY_TYPE.getKey(p.type));
        badTypes.add(p.type);
        dispose(p);
        if (active == p) {
            active = null;
        }
        return true;
    }

    /** Panel text for a mob whose preview could not be made or was switched off (badTypes). */
    private void drawPreviewOff(GuiGraphics g) {
        List<FormattedCharSequence> lines = this.font.split(Component.translatable("encephalon.screen.preview_off"), Math.max(40, rw - 16));
        int y0 = (TOP + viewBottom()) / 2 - lines.size() * 5;
        for (int i = 0; i < lines.size(); i++) {
            g.drawCenteredString(this.font, lines.get(i), rx + rw / 2, y0 + i * 10, 0xFFAA00);
        }
    }""")
edit("""            Preview p = current();
            if (p != null) {
                drawActive(g, p, realPartial);
            }""",
     """            Preview p = current();
            if (p != null) {
                drawActive(g, p, realPartial);
            } else if (badTypes.contains(BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse(scope.id())))) {
                drawPreviewOff(g);
            }""")

out = text
for i, (old, new) in enumerate(EDITS, 1):
    old = old.replace("\n", nl)
    new = new.replace("\n", nl)
    n = out.count(old)
    if n != 1:
        sys.exit("Edit %d matched %d times (expected 1); nothing was changed. Anchor: %s" % (i, n, old.strip()[:70]))
    out = out.replace(old, new)

if has_heavy:
    # tighten the limits of the earlier heavy-mob guard
    for name, old_v, new_v in (("HEAVY_FRAME_NANOS", "30_000_000L", "12_000_000L"),
                               ("HUGE_TICK_NANOS", "40_000_000L", "25_000_000L"),
                               ("HEAVY_DRAW_NANOS", "50_000_000L", "30_000_000L")):
        out = re.sub(r"(%s = )%s" % (name, re.escape(old_v)), r"\g<1>" + new_v, out)

shutil.copyfile(PATH, PATH + ".bak")
with open(PATH, "w", newline="", encoding="utf-8") as f:
    f.write(out)
print("Patched %s (%d edits%s). Backup: %s.bak" % (PATH, len(EDITS), ", heavy guard already present: limits tightened" if has_heavy else "", PATH))
