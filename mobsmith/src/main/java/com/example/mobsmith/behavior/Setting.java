package com.example.mobsmith.behavior;

/**
 * A typed parameter. multiplier=true stacks on the inherited value; false means an absolute value that replaces it.
 */
public record Setting(String key, double def, double min, double max, String unit, boolean multiplier) {
    public double clamp(double v) {
        return Math.max(min, Math.min(max, v));
    }
}
