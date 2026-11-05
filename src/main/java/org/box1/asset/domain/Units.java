package org.box1.asset.domain;

/** Converts accepted alternate units into a metric's canonical unit. */
public final class Units {
    private Units() {
    }

    public static double toCanonical(double value, String unit, String canonical) {
        if (unit.equals(canonical)) {
            return value;
        }
        if ("F".equals(unit) && "C".equals(canonical)) {
            return (value - 32.0) * 5.0 / 9.0;
        }
        throw new IllegalArgumentException("No conversion from " + unit + " to " + canonical);
    }
}
