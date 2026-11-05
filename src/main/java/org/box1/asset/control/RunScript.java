package org.box1.asset.control;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.box1.asset.config.AssetConfig;
import org.box1.asset.domain.MetricCatalog;
import org.box1.asset.domain.Threshold;

/**
 * The whole content of a run as a pure function of (seed, configuration, start instant, catalog) (D-005, D-006).
 * Nothing here reads the clock, so replaying a seed reproduces the sequence exactly; pacing is the executor's job.
 */
public final class RunScript implements Iterator<Emission> {
    static final int MIN_GAP_MILLIS = 5_000;
    static final int MAX_GAP_MILLIS = 30_000;

    private final RunConfig config;
    private final MetricCatalog catalog;
    private final Instant start;
    private final int maxBody;
    private final int maxBurst;
    private final long maxPauseMillis;
    private final SeededRandom values;
    private final SeededRandom schedule;
    private final SeededRandom targets;
    private final SeededRandom extras;
    private final long tickMillis;
    private final long endMillis;
    private final Deque<Emission> pending = new ArrayDeque<>();
    private long tick;
    private long index;
    private Emission.Injection current;
    private long nextInjectionAt;
    private int[] outOfOrder;
    private final Map<String, Map<String, Object>> lastByTarget = new LinkedHashMap<>();

    public RunScript(long seed, RunConfig config, Instant start, MetricCatalog catalog, int maxBody, int maxBurst,
            Duration maxPause) {
        this.config = config;
        this.catalog = catalog;
        this.start = start;
        this.maxBody = maxBody;
        this.maxBurst = maxBurst;
        this.maxPauseMillis = maxPause.toMillis();
        this.values = SeededRandom.stream(seed, 0);
        this.schedule = SeededRandom.stream(seed, 1);
        this.targets = SeededRandom.stream(seed, 2);
        this.extras = SeededRandom.stream(seed, 3);
        this.tickMillis = 1000L / config.ratePerAsset();
        this.endMillis = config.durationSeconds() * 1000L;
        this.nextInjectionAt = config.kind() == RunKind.CHAOS ? gap() : Long.MAX_VALUE;
    }

    @Override
    public boolean hasNext() {
        fill();
        return !pending.isEmpty();
    }

    @Override
    public Emission next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        return pending.removeFirst();
    }

    /** Offset of the next emission, or -1 when the script is finished. */
    public long peekOffset() {
        return hasNext() ? pending.peekFirst().offsetMillis() : -1;
    }

    private void fill() {
        while (pending.isEmpty()) {
            long offset = tick * tickMillis;
            if (offset >= endMillis) {
                return;
            }
            emitTick(offset);
            tick++;
        }
    }

    private void emitTick(long offset) {
        if (current != null && offset >= current.endMillis()) {
            current = null;
            outOfOrder = null;
        }
        if (current == null && offset >= nextInjectionAt) {
            startInjection(offset);
        }
        for (int s = 0; s < config.sites(); s++) {
            for (int a = 0; a < config.assetsPerSite(); a++) {
                for (String metric : config.metrics()) {
                    baseReading(offset, s, a, metric);
                }
            }
        }
        if (current != null && current.entry() == ChaosEntry.BURST) {
            int multiplier = Integer.parseInt(current.parameters().replace("x", ""));
            for (int extra = 1; extra < multiplier; extra++) {
                long subOffset = offset + extra * tickMillis / multiplier;
                for (int s = 0; s < config.sites(); s++) {
                    for (int a = 0; a < config.assetsPerSite(); a++) {
                        for (String metric : config.metrics()) {
                            AssetConfig.MetricPolicy policy = catalog.require(metric);
                            add(Emission.reading(index, subOffset, fields(s, a, metric, normal(policy, extras), subOffset, 0)));
                        }
                    }
                }
            }
        }
    }

    /** The value is always drawn, so chaos never shifts the load sequence (stream 0). */
    private void baseReading(long offset, int s, int a, String metric) {
        AssetConfig.MetricPolicy policy = catalog.require(metric);
        double value = normal(policy, values);
        String target = RunConfig.assetId(s, a) + "/" + metric;
        String asset = RunConfig.assetId(s, a);
        long measured = offset;
        if (current != null && current.target().startsWith(asset)) {
            boolean exact = current.target().equals(target);
            Threshold threshold = catalog.threshold(asset, RunConfig.siteId(s), metric);
            switch (current.entry()) {
                case STALE_ASSET -> {
                    return;
                }
                case SUSTAINED_BREACH -> value = exact ? threshold.warning() + 0.3 * (threshold.critical() - threshold.warning()) : value;
                case FLAP -> {
                    if (exact) {
                        value = (tick % 2 == 0) ? threshold.warning() + 0.05 * (threshold.critical() - threshold.warning())
                                : threshold.clearBelow() + 0.5 * (threshold.warning() - threshold.clearBelow());
                    }
                }
                case CLOCK_SKEW -> measured = offset + Long.parseLong(current.parameters().replace("ms", ""));
                case OUT_OF_ORDER -> measured = shuffled(offset);
                default -> {
                }
            }
        }
        Map<String, Object> fields = fields(s, a, metric, value, measured, 0);
        lastByTarget.put(target, fields);
        add(Emission.reading(index, offset, fields));
    }

    private long shuffled(long offset) {
        int block = outOfOrder.length;
        long position = (offset - current.startMillis()) / tickMillis;
        long blockStart = position - position % block;
        return current.startMillis() + (blockStart + outOfOrder[(int) (position % block)]) * tickMillis;
    }

    private void startInjection(long offset) {
        ChaosEntry[] entries = ChaosEntry.values();
        ChaosEntry entry = entries[schedule.nextInt(0, entries.length - 1)];
        int s = targets.nextInt(0, config.sites() - 1);
        int a = targets.nextInt(0, config.assetsPerSite() - 1);
        String metric = config.metrics().get(targets.nextInt(0, config.metrics().size() - 1));
        String asset = RunConfig.assetId(s, a);
        String target = asset + "/" + metric;
        long duration;
        String parameters;
        switch (entry) {
            case BURST -> {
                duration = targets.nextInt(5, 15) * 1000L;
                parameters = targets.nextInt(2, maxBurst) + "x";
                target = "all";
            }
            case SUSTAINED_BREACH, FLAP, STALE_ASSET -> {
                duration = targets.nextInt(10, 30) * 1000L;
                parameters = "";
            }
            case CLOCK_SKEW -> {
                duration = targets.nextInt(5, 15) * 1000L;
                long minutes = targets.nextDouble() < 0.5 ? targets.nextInt(2, 10) : -targets.nextInt(1, 30);
                parameters = (minutes * 60_000L) + "ms";
                target = asset;
            }
            case OUT_OF_ORDER -> {
                duration = targets.nextInt(5, 15) * 1000L;
                int block = targets.nextInt(3, 8);
                outOfOrder = permutation(block);
                parameters = "block=" + block;
                target = asset;
            }
            case PRODUCER_PAUSE -> {
                duration = Math.min(maxPauseMillis, targets.nextInt(5, 30) * 1000L);
                parameters = duration + "ms";
                target = "producer";
            }
            default -> {
                duration = 0;
                parameters = "";
            }
        }
        current = new Emission.Injection(entry, offset, offset + duration, target, parameters);
        add(Emission.marker(index, offset, current));
        switch (entry) {
            case SPIKE -> {
                Threshold threshold = catalog.threshold(asset, RunConfig.siteId(s), metric);
                double value = threshold.critical() * targets.between(1.2, 1.5);
                add(Emission.reading(index, offset, fields(s, a, metric, value, offset, 1)));
            }
            case DUPLICATE -> {
                Map<String, Object> previous = lastByTarget.get(target);
                int count = targets.nextInt(1, 10);
                for (int i = 0; previous != null && i < count; i++) {
                    add(Emission.reading(index, offset, new LinkedHashMap<>(previous)));
                }
            }
            case INVALID_UNIT -> {
                int count = targets.nextInt(1, 5);
                for (int i = 0; i < count; i++) {
                    Map<String, Object> bad = fields(s, a, metric, normal(catalog.require(metric), extras), offset, 2 + i);
                    bad.put("unit", "furlong");
                    add(Emission.reading(index, offset, bad));
                }
            }
            case INVALID_PAYLOAD -> {
                int count = targets.nextInt(1, 5);
                for (int i = 0; i < count; i++) {
                    Map<String, Object> bad = fields(s, a, metric, 1.0, offset, 2 + i);
                    switch (targets.nextInt(0, 3)) {
                        case 0 -> bad.remove("siteId");
                        case 1 -> bad.put("value", Double.NaN);
                        case 2 -> bad.put("value", catalog.require(metric).max() * 10 + 1);
                        default -> bad.put("metricName", "humidity");
                    }
                    add(Emission.reading(index, offset, bad));
                }
            }
            case OVERSIZE_PAYLOAD -> {
                int count = targets.nextInt(1, 3);
                for (int i = 0; i < count; i++) {
                    add(Emission.raw(index, offset, oversize(maxBody + 1 + targets.nextInt(0, 2048))));
                }
            }
            case PRODUCER_PAUSE -> add(Emission.pause(index, offset, Duration.ofMillis(duration)));
            default -> {
            }
        }
        nextInjectionAt = offset + duration + gap();
    }

    private int[] permutation(int size) {
        int[] order = new int[size];
        for (int i = 0; i < size; i++) {
            order[i] = i;
        }
        for (int i = size - 1; i > 0; i--) {
            int j = targets.nextInt(0, i);
            int swap = order[i];
            order[i] = order[j];
            order[j] = swap;
        }
        return order;
    }

    private long gap() {
        return schedule.nextInt(MIN_GAP_MILLIS, MAX_GAP_MILLIS);
    }

    /** Normal operation sits below the clear level, so load alone never alerts. */
    private static double normal(AssetConfig.MetricPolicy policy, SeededRandom random) {
        double headroom = policy.clearBelow() - policy.nominal();
        double noise = random.nextDouble() + random.nextDouble() + random.nextDouble() - 1.5;
        return round(policy.nominal() + noise * 0.4 * headroom);
    }

    /** {@code jitterMillis} keeps extra readings from colliding with a base reading's natural key. */
    private Map<String, Object> fields(int s, int a, String metric, double value, long measuredOffset, int jitterMillis) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("assetId", RunConfig.assetId(s, a));
        fields.put("siteId", RunConfig.siteId(s));
        fields.put("metricName", metric);
        fields.put("measuredAt", start.plusMillis(measuredOffset + jitterMillis).toString());
        fields.put("value", Double.isFinite(value) ? round(value) : value);
        fields.put("unit", catalog.require(metric).unit());
        return fields;
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static byte[] oversize(int size) {
        String prefix = "{\"assetId\":\"asset_load_01_01\",\"note\":\"";
        String suffix = "\"}";
        int padding = Math.max(0, size - prefix.length() - suffix.length());
        return (prefix + "x".repeat(padding) + suffix).getBytes(StandardCharsets.UTF_8);
    }

    private void add(Emission emission) {
        pending.addLast(new Emission(index++, emission.offsetMillis(), emission.fields(), emission.raw(), emission.pause(),
                emission.injection()));
    }

    /** The first {@code limit} emissions, for preview and digest. Uses a fresh script. */
    public static List<Emission> first(long seed, RunConfig config, Instant start, MetricCatalog catalog, int maxBody,
            int maxBurst, Duration maxPause, int limit) {
        RunScript script = new RunScript(seed, config, start, catalog, maxBody, maxBurst, maxPause);
        List<Emission> list = new ArrayList<>();
        while (list.size() < limit && script.hasNext()) {
            list.add(script.next());
        }
        return list;
    }
}
