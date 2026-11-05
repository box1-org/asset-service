package org.box1.asset.control;

import java.util.HexFormat;
import java.util.Locale;

/**
 * SplitMix64 (D-005). Implemented here so the sequence is fixed by this code, not by a JDK implementation detail.
 * Not for security purposes.
 */
public final class SeededRandom {
    private static final long GOLDEN = 0x9E3779B97F4A7C15L;
    private long state;

    public SeededRandom(long seed) {
        this.state = seed;
    }

    /** Independent stream {@code index} of a run seed: 0 values, 1 chaos schedule, 2 chaos targets. */
    public static SeededRandom stream(long seed, int index) {
        return new SeededRandom(mix(seed ^ (GOLDEN * (index + 1L))));
    }

    public long nextLong() {
        state += GOLDEN;
        return mix(state);
    }

    /** Uniform in [0, 1). */
    public double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }

    /** Uniform in [min, max]. */
    public int nextInt(int min, int max) {
        return min + (int) Math.floor(nextDouble() * (max - min + 1));
    }

    public double between(double min, double max) {
        return min + nextDouble() * (max - min);
    }

    private static long mix(long value) {
        long z = value;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** 16 lowercase hex characters. */
    public static String format(long seed) {
        return HexFormat.of().toHexDigits(seed);
    }

    public static long parse(String text) {
        if (text == null || !text.matches("[0-9a-f]{16}")) {
            throw new IllegalArgumentException("seed must be 16 lowercase hex characters");
        }
        return Long.parseUnsignedLong(text.toLowerCase(Locale.ROOT), 16);
    }
}
