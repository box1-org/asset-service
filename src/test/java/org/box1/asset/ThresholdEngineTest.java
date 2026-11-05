package org.box1.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.box1.asset.domain.AlertState;
import org.box1.asset.domain.Threshold;
import org.box1.asset.domain.ThresholdEngine;
import org.box1.asset.domain.ThresholdEngine.Transition;
import org.junit.jupiter.api.Test;

/** TEL-ALERT-002: hysteresis, debounce and deduplication (D-008). */
class ThresholdEngineTest {
    private final ThresholdEngine engine = new ThresholdEngine(3);
    private final Threshold threshold = new Threshold(80, 95, 75);

    private List<Transition> run(double... values) {
        AlertState state = AlertState.NONE;
        List<Transition> transitions = new ArrayList<>();
        for (double value : values) {
            ThresholdEngine.Outcome outcome = engine.evaluate(state, threshold, value);
            state = outcome.next();
            if (outcome.transition() != Transition.NONE && outcome.transition() != Transition.REPEATED) {
                transitions.add(outcome.transition());
            }
        }
        return transitions;
    }

    @Test
    void flappingAcrossWarningGivesOneAlertThenOneClear() {
        double[] values = new double[44];
        for (int i = 0; i < 40; i++) {
            values[i] = i % 2 == 0 ? 80.75 : 77.5;
        }
        values[40] = 60;
        values[41] = 61;
        values[42] = 62;
        values[43] = 63;
        assertEquals(List.of(Transition.ACTIVATED, Transition.CLEARED), run(values));
    }

    @Test
    void sustainedBreachThenRecoveryIsOneAlertAndOneClear() {
        assertEquals(List.of(Transition.ACTIVATED, Transition.CLEARED),
                run(60, 82, 83, 84, 85, 86, 87, 88, 89, 70, 70, 70, 70));
    }

    @Test
    void repeatedBreachingReadingsDoNotSpam() {
        double[] values = new double[200];
        java.util.Arrays.fill(values, 85);
        assertEquals(List.of(Transition.ACTIVATED), run(values));
    }

    @Test
    void singleWarningSpikeIsDebounced() {
        assertEquals(List.of(), run(60, 85, 60, 85, 60, 85, 60));
    }

    @Test
    void criticalActivatesImmediatelyAndWarningEscalates() {
        assertEquals(List.of(Transition.ACTIVATED), run(60, 99));
        assertEquals(List.of(Transition.ACTIVATED, Transition.ESCALATED), run(85, 85, 85, 96, 96));
    }

    @Test
    void bandReadingResetsTheClearCount() {
        assertEquals(List.of(Transition.ACTIVATED), run(99, 70, 70, 78, 70, 70));
        assertEquals(List.of(Transition.ACTIVATED, Transition.CLEARED), run(99, 70, 70, 78, 70, 70, 70));
    }
}
