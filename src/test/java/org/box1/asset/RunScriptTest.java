package org.box1.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.box1.asset.control.ChaosEntry;
import org.box1.asset.control.Emission;
import org.box1.asset.control.RunConfig;
import org.box1.asset.control.RunKind;
import org.box1.asset.control.RunScript;
import org.box1.asset.control.SeededRandom;
import org.box1.asset.control.SequenceDigest;
import org.box1.asset.domain.MetricCatalog;
import org.junit.jupiter.api.Test;

/** TEL-LOAD-002 and TEL-CHAOS-001: seeded, reproducible, telemetry-only (D-005, D-006). */
class RunScriptTest {
    private static final MetricCatalog CATALOG = TestCatalog.catalog();
    private static final List<String> METRICS = List.of("temperature", "vibration");

    private static List<Emission> all(long seed, RunKind kind, Instant start, int seconds) {
        RunScript script = new RunScript(seed, new RunConfig(kind, 2, 3, METRICS, 2, seconds), start, CATALOG, 4096, 5,
                Duration.ofSeconds(30));
        List<Emission> list = new ArrayList<>();
        script.forEachRemaining(list::add);
        return list;
    }

    private static List<String> canonical(List<Emission> emissions, Instant start) {
        return emissions.stream().map(e -> e.canonical(start)).toList();
    }

    @Test
    void sameSeedGivesTheSameSequenceWheneverItStarts() {
        Instant a = Instant.parse("2026-11-05T10:00:00Z");
        Instant b = Instant.parse("2026-11-06T17:31:07.250Z");
        long seed = SeededRandom.parse("5eed00000000002a");
        assertEquals(canonical(all(seed, RunKind.CHAOS, a, 300), a), canonical(all(seed, RunKind.CHAOS, b, 300), b));
        assertEquals(SequenceDigest.of(all(seed, RunKind.CHAOS, a, 300), a), SequenceDigest.of(all(seed, RunKind.CHAOS, b, 300), b));
    }

    @Test
    void differentSeedsDiffer() {
        Instant a = Instant.EPOCH;
        assertNotEquals(canonical(all(1, RunKind.LOAD, a, 30), a), canonical(all(2, RunKind.LOAD, a, 30), a));
    }

    @Test
    void loadAloneStaysBelowTheClearLevelAndIsOnlyValidReadings() {
        for (Emission e : all(7, RunKind.LOAD, Instant.EPOCH, 120)) {
            assertTrue(e.fields() != null && e.raw() == null && e.pause() == null && e.injection() == null);
            double value = ((Number) e.fields().get("value")).doubleValue();
            String metric = (String) e.fields().get("metricName");
            assertTrue(value < CATALOG.require(metric).clearBelow(), metric + " " + value);
        }
    }

    @Test
    void chaosDoesNotShiftTheLoadValues() {
        long seed = 99;
        List<String> load = all(seed, RunKind.LOAD, Instant.EPOCH, 60).stream().map(e -> e.fields().get("assetId") + "@"
                + e.fields().get("measuredAt") + "/" + e.fields().get("metricName") + "=" + e.fields().get("value")).toList();
        List<String> chaos = all(seed, RunKind.CHAOS, Instant.EPOCH, 60).stream()
                .filter(e -> e.fields() != null)
                .map(e -> e.fields().get("assetId") + "@" + e.fields().get("measuredAt") + "/" + e.fields().get("metricName") + "="
                        + e.fields().get("value")).toList();
        long unchanged = load.stream().filter(chaos::contains).count();
        assertTrue(unchanged > load.size() * 0.7, "most base readings are untouched by chaos: " + unchanged + "/" + load.size());
    }

    @Test
    void chaosUsesOnlyTheCatalogWithinItsBounds() {
        Set<ChaosEntry> seen = EnumSet.noneOf(ChaosEntry.class);
        for (long seed = 0; seed < 40; seed++) {
            long previousEnd = -1;
            for (Emission e : all(seed, RunKind.CHAOS, Instant.EPOCH, 600)) {
                if (e.injection() == null) {
                    continue;
                }
                Emission.Injection injection = e.injection();
                seen.add(injection.entry());
                long length = injection.endMillis() - injection.startMillis();
                assertTrue(length <= 30_000, injection.describe());
                assertTrue(injection.startMillis() >= previousEnd + 5_000 - 500, "gap at least 5 s: " + injection.describe());
                previousEnd = injection.endMillis();
                if (injection.entry() == ChaosEntry.BURST) {
                    int multiplier = Integer.parseInt(injection.parameters().replace("x", ""));
                    assertTrue(multiplier >= 2 && multiplier <= 5);
                }
            }
        }
        assertEquals(EnumSet.allOf(ChaosEntry.class), seen, "40 seeded 10-minute runs exercise every catalog entry");
    }

    @Test
    void pauseNeverExceedsTheConfiguredMaximum() {
        RunScript script = new RunScript(3, new RunConfig(RunKind.CHAOS, 1, 1, METRICS, 1, 900), Instant.EPOCH, CATALOG, 4096, 5,
                Duration.ofSeconds(8));
        script.forEachRemaining(e -> {
            if (e.pause() != null) {
                assertTrue(e.pause().compareTo(Duration.ofSeconds(8)) <= 0);
            }
        });
    }

    @Test
    void aProducerPauseNeverOutlivesItsRunAndForwardSkewIsAlwaysRejectable() {
        for (long seed = 0; seed < 60; seed++) {
            RunConfig config = new RunConfig(RunKind.CHAOS, 1, 2, METRICS, 1, 40);
            RunScript script = new RunScript(seed, config, Instant.EPOCH, CATALOG, 4096, 5, Duration.ofSeconds(30));
            while (script.hasNext()) {
                Emission e = script.next();
                if (e.pause() != null) {
                    assertTrue(e.offsetMillis() + e.pause().toMillis() <= 40_000, "pause ends by the run end: " + e.offsetMillis() + "+" + e.pause());
                }
                if (e.injection() != null && e.injection().entry() == ChaosEntry.CLOCK_SKEW) {
                    long skew = Long.parseLong(e.injection().parameters().replace("ms", ""));
                    assertTrue(skew < 0 || skew > 5 * 60_000, "forward skew is beyond the 5 min limit: " + skew);
                }
            }
        }
    }

    @Test
    void seedFormatIsSixteenLowercaseHex() {
        assertEquals("5eed00000000002a", SeededRandom.format(SeededRandom.parse("5eed00000000002a")));
        assertEquals("ffffffffffffffff", SeededRandom.format(-1L));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> SeededRandom.parse("5EED"));
    }
}
