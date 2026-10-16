# Big-mob preview fix

Unzip into the repo root (it overwrites EncephalonConfig.java and en_us.json), then from the repo root:

    python tools/apply_big_mob_fix.py
    ./gradlew runClient

If a mob is still slow, send the "Mob Editor preview: ..." lines from logs/latest.log (they name the mob, the phase and the
millisecond cost). New client config keys (encephalon-client.toml): hoverPrewarm (default false), previewBreakerMs (default 120).
