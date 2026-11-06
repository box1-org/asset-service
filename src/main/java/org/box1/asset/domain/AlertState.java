package org.box1.asset.domain;

/** Persisted per-(asset, metric) evaluation state. Counters move a pair toward a transition (D-008). */
public record AlertState(boolean active, Severity severity, int breachStreak, int clearStreak) {
    public static final AlertState NONE = new AlertState(false, null, 0, 0);

    public enum Severity { WARNING, CRITICAL }
}
