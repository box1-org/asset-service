package org.box1.asset.control;

import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;

/** One step of a run, at a logical offset from its start. Exactly one of the payload fields is set. */
public record Emission(long index, long offsetMillis, Map<String, Object> fields, byte[] raw, Duration pause,
        Injection injection) {

    public static Emission reading(long index, long offset, Map<String, Object> fields) {
        return new Emission(index, offset, fields, null, null, null);
    }

    public static Emission raw(long index, long offset, byte[] raw) {
        return new Emission(index, offset, null, raw, null, null);
    }

    public static Emission pause(long index, long offset, Duration pause) {
        return new Emission(index, offset, null, null, pause, null);
    }

    public static Emission marker(long index, long offset, Injection injection) {
        return new Emission(index, offset, null, null, null, injection);
    }

    /**
     * The canonical line hashed into the run's sequence digest (D-005). {@code measuredAt} is written relative to the
     * run start, so the same seed and configuration give the same line whenever the run starts.
     */
    public String canonical(java.time.Instant start) {
        StringBuilder line = new StringBuilder().append(index).append('|').append(offsetMillis).append('|');
        if (fields != null) {
            line.append("R|");
            new TreeMap<>(fields).forEach((k, v) -> line.append(k).append('=').append(relative(k, v, start)).append(';'));
        } else if (raw != null) {
            line.append("B|").append(raw.length);
        } else if (pause != null) {
            line.append("P|").append(pause.toMillis());
        } else {
            line.append("I|").append(injection.describe());
        }
        return line.toString();
    }

    private static Object relative(String key, Object value, java.time.Instant start) {
        if ("measuredAt".equals(key) && value instanceof String text) {
            try {
                return java.time.Duration.between(start, java.time.Instant.parse(text)).toMillis() + "ms";
            } catch (java.time.format.DateTimeParseException error) {
                return text;
            }
        }
        return value;
    }

    /** A chaos injection as recorded in the audit. */
    public record Injection(ChaosEntry entry, long startMillis, long endMillis, String target, String parameters) {
        public String describe() {
            return entry + " " + target + " " + startMillis + "-" + endMillis + "ms " + parameters;
        }
    }
}
