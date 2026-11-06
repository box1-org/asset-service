package org.box1.asset.domain;

/**
 * Pure hysteresis and debounce (D-008). While inactive, readings at or above warning count toward activation, readings
 * inside the hysteresis band (clear level to warning) neither count nor reset, and a reading below the clear level
 * resets the count. One reading at or above critical activates at once. While active, {@code trigger} consecutive
 * readings below the clear level clear the alert; any other reading resets that count. A value flapping across the
 * warning level therefore raises one alert and holds it until values return below the clear level.
 */
public final class ThresholdEngine {
    public static final int DEFAULT_TRIGGER = 3;

    private final int trigger;

    public ThresholdEngine(int trigger) {
        if (trigger < 1) {
            throw new IllegalArgumentException("trigger must be at least 1");
        }
        this.trigger = trigger;
    }

    public Outcome evaluate(AlertState state, Threshold threshold, double value) {
        AlertState.Severity level = value >= threshold.critical() ? AlertState.Severity.CRITICAL
                : value >= threshold.warning() ? AlertState.Severity.WARNING : null;
        if (!state.active()) {
            if (level == AlertState.Severity.CRITICAL) {
                return new Outcome(Transition.ACTIVATED, active(level), threshold.critical());
            }
            if (level == AlertState.Severity.WARNING) {
                int streak = state.breachStreak() + 1;
                if (streak >= trigger) {
                    return new Outcome(Transition.ACTIVATED, active(level), threshold.warning());
                }
                return new Outcome(Transition.NONE, new AlertState(false, null, streak, 0), 0);
            }
            if (value >= threshold.clearBelow()) {
                return new Outcome(Transition.NONE, state, 0);
            }
            return new Outcome(Transition.NONE, AlertState.NONE, 0);
        }
        if (value < threshold.clearBelow()) {
            int streak = state.clearStreak() + 1;
            if (streak >= trigger) {
                return new Outcome(Transition.CLEARED, AlertState.NONE, threshold.clearBelow());
            }
            return new Outcome(Transition.NONE, new AlertState(true, state.severity(), 0, streak), 0);
        }
        if (level == AlertState.Severity.CRITICAL && state.severity() == AlertState.Severity.WARNING) {
            return new Outcome(Transition.ESCALATED, active(AlertState.Severity.CRITICAL), threshold.critical());
        }
        Transition transition = level == null ? Transition.NONE : Transition.REPEATED;
        return new Outcome(transition, active(state.severity()), 0);
    }

    private static AlertState active(AlertState.Severity severity) {
        return new AlertState(true, severity, 0, 0);
    }

    public enum Transition { NONE, ACTIVATED, REPEATED, ESCALATED, CLEARED }

    /** {@code threshold} is the crossed level for ACTIVATED, ESCALATED and CLEARED, else 0. */
    public record Outcome(Transition transition, AlertState next, double threshold) {
    }
}
