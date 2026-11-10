package org.box1.asset.control;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.box1.asset.config.AssetConfig;
import org.box1.asset.domain.MetricCatalog;
import org.box1.asset.domain.RejectedReadingException;
import org.box1.asset.ingest.IngestionPipeline;
import org.box1.asset.ingest.ReadingPublisher;
import org.box1.asset.ingest.Source;
import org.box1.asset.store.StoreException;
import org.jboss.logging.Logger;

/**
 * Arms, starts, paces and stops load and chaos runs. Every limit is checked here, never only in the page (D-004).
 * Runs execute only in the pod that started them; a restarted pod never resumes one (D-007).
 */
@ApplicationScoped
public class RunManager {
    private static final Logger LOG = Logger.getLogger(RunManager.class);
    private static final long TICK_MILLIS = 50;
    private static final int MAX_PER_TICK = 500;
    static final int MAX_ARMED = 4;

    private final String owner = "pod-" + UUID.randomUUID();
    private final Map<String, ActiveRun> active = new ConcurrentHashMap<>();
    private final RunStore runs;
    private final RunTransitions transitions;
    private final ChaosGate gate;
    private final MetricCatalog catalog;
    private final IngestionPipeline pipeline;
    private final ReadingPublisher publisher;
    private final AssetConfig config;
    private final MeterRegistry registry;
    private final Tracer tracer;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();
    private ScheduledExecutorService ticker;
    /** Serializes start-and-launch with stop-all and reset in this pod (review PERS-2, ARCH-7). */
    private final Object lifecycle = new Object();
    private volatile String pausedBy;
    private volatile long heartbeatFailingSinceNanos;
    /** Last time every local run's heartbeat reached the database; checked by each tick without touching the database. */
    private volatile long lastHeartbeatOkNanos = System.nanoTime();

    public RunManager(RunStore runs, RunTransitions transitions, ChaosGate gate, MetricCatalog catalog, IngestionPipeline pipeline,
            ReadingPublisher publisher, AssetConfig config, MeterRegistry registry, Tracer tracer) {
        this.runs = runs;
        this.transitions = transitions;
        this.gate = gate;
        this.catalog = catalog;
        this.pipeline = pipeline;
        this.publisher = publisher;
        this.config = config;
        this.registry = registry;
        this.tracer = tracer;
        registry.gauge("asset.control.runs.active", active, Map::size);
    }

    void onStart(@Observes StartupEvent event) {
        ticker = Executors.newScheduledThreadPool(2, Thread.ofPlatform().name("asset-run-", 0).daemon().factory());
        LOG.infof("Run control instance %s; chaos gate: %s", owner, gate.decide().code());
    }

    void onStop(@Observes ShutdownEvent event) {
        for (ActiveRun run : List.copyOf(active.values())) {
            end(run, "ABORTED", "pod shutting down; not resumed", "system", "ABORTED_SHUTDOWN");
        }
        if (ticker != null) {
            ticker.shutdownNow();
        }
    }

    public String owner() {
        return owner;
    }

    /** Validates the request against every limit and the gate; records ARM. The page cannot skip any of this. */
    public Armed arm(String actor, RunRequest request) {
        if (!config.control().enabled()) {
            throw denied(actor, null, 403, "CONTROL_DISABLED", "Run control is disabled by configuration");
        }
        RunConfig runConfig = validate(actor, request);
        if (runConfig.kind() == RunKind.CHAOS) {
            ChaosGate.Decision decision = gate.decide();
            if (!decision.open()) {
                throw denied(actor, null, 403, decision.code(), decision.message());
            }
        }
        long seed = request.seed() == null || request.seed().isBlank() ? random.nextLong() : parseSeed(actor, request.seed());
        String seedText = SeededRandom.format(seed);
        String runId = "run_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        byte[] tokenBytes = new byte[16];
        random.nextBytes(tokenBytes);
        String token = HexFormat.of().formatHex(tokenBytes);
        String configJson = json(runConfig);
        long armSeconds = config.control().armLifetime().toSeconds();
        String expected = SequenceDigest.of(RunScript.first(seed, runConfig, Instant.EPOCH, catalog, config.ingest().maxBodyBytes(),
                config.chaos().maxBurstMultiplier(), config.chaos().maxPause(), SequenceDigest.LENGTH), Instant.EPOCH);
        transitions.arm(runId, runConfig.kind(), actor, seedText, configJson, gate.namespace(), armSeconds, RunTransitions.hash(token), owner,
                json(Map.of("seed", seedText, "config", runConfig, "expectedDigest", expected)), MAX_ARMED);
        RunRecord record = runs.find(runId).orElseThrow();
        return new Armed(runId, seedText, token, record.armedUntil(), gate.namespace(), gate.environment(), runConfig.baseRate(), expected);
    }

    /** Starts an armed run after the confirmation token and the typed namespace match. */
    public RunRecord start(String actor, String runId, String confirmToken, String confirmNamespace) {
        RunRecord armed = runs.find(runId).orElseThrow(() -> new ControlException(404, "RUN_NOT_FOUND", "No run " + runId));
        RunConfig runConfig = parse(armed.config());
        // Checked again at start: the reserved list may have changed since the run was armed.
        List<String> reserved = runConfig.reservedSiteIds(reservedSites());
        if (!reserved.isEmpty()) {
            throw denied(actor, runId, 422, "RESERVED_SITE", "The generator never emits real edge sites; this run would emit " + reserved);
        }
        long duration = runConfig.durationSeconds();
        long ttl = duration + config.control().ttlGrace().toSeconds();
        synchronized (lifecycle) {
            RunRecord started = transitions.start(actor, runId, confirmToken, confirmNamespace, owner, duration, ttl);
            launch(started, runConfig);
            return started;
        }
    }

    public boolean stop(String actor, String runId) {
        runs.find(runId).orElseThrow(() -> new ControlException(404, "RUN_NOT_FOUND", "No run " + runId));
        ActiveRun run = active.get(runId);
        if (run != null) {
            return end(run, "STOPPED", "stopped by " + actor, actor, "STOP");
        }
        return transitions.finish(runId, "STOPPED", "stopped by " + actor, actor, "STOP", "{}");
    }

    /** Test hook: the run that last paused the producer, if it has not been resumed. */
    String pausedBy() {
        return pausedBy;
    }

    /** Stops every run everywhere this database knows of, and resumes the producer. */
    public List<String> stopAll(String actor) {
        return stopEverything(actor, "STOP_ALL");
    }

    /** Stop-all plus clearing service-local fault state. Deletes no readings, alerts or audit. */
    public List<String> reset(String actor) {
        return stopEverything(actor, "RESET");
    }

    private List<String> stopEverything(String actor, String action) {
        synchronized (lifecycle) {
            for (ActiveRun run : active.values()) {
                run.cancel();
            }
            active.clear();
            resumeProducer();
            List<String> stopped = transitions.stopAll(actor, action);
            LOG.infof("%s by %s stopped %d runs", action, actor, stopped.size());
            return stopped;
        }
    }

    private void resumeProducer() {
        pausedBy = null;
        publisher.resume();
    }

    public Map<String, ActiveRun> active() {
        return Map.copyOf(active);
    }

    /** The first N emissions for a seed and configuration, without publishing anything. */
    public Preview preview(String actor, RunRequest request, int limit) {
        RunConfig runConfig = validate(actor, request);
        long seed = parseSeed(actor, request.seed());
        List<Emission> emissions = RunScript.first(seed, runConfig, Instant.EPOCH, catalog, config.ingest().maxBodyBytes(),
                config.chaos().maxBurstMultiplier(), config.chaos().maxPause(), Math.min(limit, SequenceDigest.LENGTH));
        List<String> lines = emissions.stream().map(e -> e.canonical(Instant.EPOCH)).toList();
        String digest = SequenceDigest.of(RunScript.first(seed, runConfig, Instant.EPOCH, catalog, config.ingest().maxBodyBytes(),
                config.chaos().maxBurstMultiplier(), config.chaos().maxPause(), SequenceDigest.LENGTH), Instant.EPOCH);
        return new Preview(SeededRandom.format(seed), runConfig, digest, lines);
    }

    @Scheduled(every = "${box1.control.heartbeat:5s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void heartbeat() {
        boolean failed = false;
        for (ActiveRun run : List.copyOf(active.values())) {
            try {
                if (!runs.heartbeat(run.runId, owner, run.emitted, run.rejected, run.digest.value())) {
                    LOG.infof("Run %s is no longer running in the database; stopping locally", run.runId);
                    stopLocally(run);
                }
            } catch (StoreException error) {
                failed = true;
                LOG.warnf("Heartbeat for %s failed (%s)", run.runId, error.getMessage());
            }
        }
        if (!failed) {
            lastHeartbeatOkNanos = System.nanoTime();
        }
        failSafe(failed);
    }

    /**
     * Without the database nobody can stop a run through the console (readiness is DOWN) and the reaper cannot run, so a
     * pod that cannot heartbeat for longer than the stale limit stops its own runs (review OPS-4).
     */
    void failSafe(boolean failed) {
        if (!failed) {
            heartbeatFailingSinceNanos = 0;
            return;
        }
        long now = System.nanoTime();
        if (heartbeatFailingSinceNanos == 0) {
            heartbeatFailingSinceNanos = now;
        } else if (now - heartbeatFailingSinceNanos > config.control().staleHeartbeat().toNanos()) {
            LOG.errorf("Heartbeats failing for over %s; stopping %d local runs as a fail-safe", config.control().staleHeartbeat(),
                    active.size());
            for (ActiveRun run : List.copyOf(active.values())) {
                stopLocally(run);
            }
        }
    }

    private void stopLocally(ActiveRun run) {
        run.cancel();
        active.remove(run.runId);
        if (run.runId.equals(pausedBy)) {
            resumeProducer();
        }
    }

    @Scheduled(every = "${box1.control.reaper:5s}", delay = 2, delayUnit = TimeUnit.SECONDS,
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void reap() {
        try {
            for (String runId : transitions.reap(config.control().staleHeartbeat().toSeconds())) {
                ActiveRun run = active.get(runId);
                if (run != null) {
                    stopLocally(run);
                }
            }
        } catch (StoreException error) {
            LOG.warnf("Reaper pass failed (%s)", error.getMessage());
        }
    }

    private void launch(RunRecord record, RunConfig runConfig) {
        Instant start = record.startedAt();
        RunScript script = new RunScript(SeededRandom.parse(record.seed()), runConfig, start, catalog, config.ingest().maxBodyBytes(),
                config.chaos().maxBurstMultiplier(), config.chaos().maxPause());
        ActiveRun run = new ActiveRun(record.runId(), record.kind(), record.seed(), runConfig, script, start, System.nanoTime(),
                new SequenceDigest(start), new org.box1.asset.ingest.TokenBucket(config.control().maxRunRatePerSecond(), System::nanoTime));
        active.put(run.runId, run);
        run.future = ticker.scheduleAtFixedRate(() -> tick(run), 0, TICK_MILLIS, TimeUnit.MILLISECONDS);
        LOG.infof("Run %s started: %s seed %s, %d readings/s for %d s", run.runId, run.kind, run.seed, runConfig.baseRate(),
                runConfig.durationSeconds());
    }

    private void tick(ActiveRun run) {
        try {
            // A hung database call can stop the heartbeat itself, so the tick enforces the fail-safe without the database.
            if (System.nanoTime() - Math.max(lastHeartbeatOkNanos, run.startedNanos) > config.control().staleHeartbeat().toNanos()) {
                LOG.errorf("No successful heartbeat for over %s; stopping run %s locally as a fail-safe", config.control().staleHeartbeat(),
                        run.runId);
                stopLocally(run);
                return;
            }
            long elapsed = (System.nanoTime() - run.startedNanos) / 1_000_000;
            if (elapsed >= run.config.durationSeconds() * 1000L) {
                end(run, "COMPLETED", "duration reached", "system", "COMPLETE");
                return;
            }
            int done = 0;
            long next = run.script.peekOffset();
            while (next >= 0 && next <= elapsed && done < MAX_PER_TICK && !run.cancelled) {
                execute(run, run.script.next());
                done++;
                next = run.script.peekOffset();
            }
            if (next < 0) {
                end(run, "COMPLETED", "script finished", "system", "COMPLETE");
            }
        } catch (RuntimeException error) {
            LOG.error("Run " + run.runId + " failed; stopping it", error);
            end(run, "ABORTED", "executor error " + error.getClass().getSimpleName(), "system", "ABORTED_ERROR");
        }
    }

    private void execute(ActiveRun run, Emission emission) {
        run.digest.add(emission);
        Source source = run.kind == RunKind.CHAOS ? Source.CHAOS : Source.LOAD;
        String correlation = "run:" + run.runId;
        if (emission.injection() != null) {
            run.injection = emission.injection();
            registry.counter("asset.chaos.injections", "entry", emission.injection().entry().name().toLowerCase(java.util.Locale.ROOT))
                    .increment();
            runs.audit(run.runId, "system", "INJECT", json(Map.of("entry", emission.injection().entry().name(), "target",
                    emission.injection().target(), "startMillis", emission.injection().startMillis(), "endMillis",
                    emission.injection().endMillis(), "parameters", emission.injection().parameters())));
            return;
        }
        if (emission.pause() != null) {
            Duration pause = emission.pause().compareTo(config.chaos().maxPause()) > 0 ? config.chaos().maxPause() : emission.pause();
            pausedBy = run.runId;
            publisher.pause(pause);
            return;
        }
        if (!run.bucket.tryAcquire()) {
            // The run's actual rate, bursts included, never exceeds the per-run cap (review ARCH-2).
            run.rejected++;
            registry.counter("asset.ingest.rejected", "source", source.tag(), "reason", "run_rate").increment();
            return;
        }
        Span span = tracer.spanBuilder("asset-service run emit").setAttribute("asset.run.id", run.runId).startSpan();
        try (Scope ignored = span.makeCurrent()) {
            if (emission.raw() != null) {
                pipeline.submitRaw(emission.raw(), source, correlation);
            } else {
                pipeline.submit(emission.fields(), source, correlation);
            }
            run.emitted++;
        } catch (RejectedReadingException rejected) {
            run.rejected++;
        } finally {
            span.end();
        }
    }

    private boolean end(ActiveRun run, String state, String reason, String actor, String action) {
        stopLocally(run);
        String digest = run.digest.finish();
        try {
            runs.progress(run.runId, run.emitted, run.rejected, digest);
            return transitions.finish(run.runId, state, reason, actor, action,
                    json(Map.of("emitted", run.emitted, "rejected", run.rejected, "sequenceDigest", digest)));
        } catch (StoreException error) {
            LOG.warnf("Could not record the end of %s (%s); the reaper will close it", run.runId, error.getMessage());
            return false;
        }
    }

    RunConfig validate(String actor, RunRequest request) {
        AssetConfig.Control limits = config.control();
        if (request == null || request.kind() == null) {
            throw denied(actor, null, 422, "INVALID_REQUEST", "kind is required (LOAD or CHAOS)");
        }
        List<String> metrics = request.metrics() == null || request.metrics().isEmpty() ? catalog.names() : request.metrics();
        for (String metric : metrics) {
            if (catalog.policy(metric).isEmpty()) {
                throw denied(actor, null, 422, "UNKNOWN_METRIC", "metrics must be among " + catalog.names());
            }
        }
        if (metrics.stream().distinct().count() != metrics.size()) {
            throw denied(actor, null, 422, "INVALID_REQUEST", "metrics must not repeat");
        }
        int sites = orDefault(request.sites(), 2);
        int assets = orDefault(request.assetsPerSite(), 3);
        int rate = orDefault(request.ratePerAsset(), 1);
        int duration = orDefault(request.durationSeconds(), 120);
        range(actor, "sites", sites, 1, limits.maxSites());
        range(actor, "assetsPerSite", assets, 1, limits.maxAssetsPerSite());
        range(actor, "ratePerAsset", rate, 1, limits.maxRatePerAsset());
        range(actor, "durationSeconds", duration, 10, (int) limits.maxDuration().toSeconds());
        RunConfig runConfig = new RunConfig(request.kind(), sites, assets, List.copyOf(metrics), rate, duration);
        List<String> reserved = runConfig.reservedSiteIds(reservedSites());
        if (!reserved.isEmpty()) {
            throw denied(actor, null, 422, "RESERVED_SITE", "The generator never emits real edge sites; this run would emit " + reserved);
        }
        if (runConfig.baseRate() > limits.maxRunRatePerSecond()) {
            throw denied(actor, null, 422, "RATE_CAP", "sites x assetsPerSite x metrics x ratePerAsset = " + runConfig.baseRate()
                    + " readings/s; the cap is " + limits.maxRunRatePerSecond());
        }
        return runConfig;
    }

    /** Real edge sites (asset.control.reserved-sites) that no load or chaos run may emit. */
    java.util.Set<String> reservedSites() {
        return config.control().reservedSites().map(java.util.Set::copyOf).orElse(java.util.Set.of());
    }

    private static int orDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private void range(String actor, String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw denied(actor, null, 422, "LIMIT_EXCEEDED", name + " must be between " + min + " and " + max);
        }
    }

    private long parseSeed(String actor, String seed) {
        try {
            return SeededRandom.parse(seed);
        } catch (IllegalArgumentException error) {
            throw denied(actor, null, 422, "INVALID_SEED", error.getMessage());
        }
    }

    private ControlException denied(String actor, String runId, int status, String code, String message) {
        try {
            runs.audit(runId, actor, "DENIED", json(Map.of("code", code)));
        } catch (StoreException error) {
            LOG.warnf("Could not audit a refusal (%s)", error.getMessage());
        }
        registry.counter("asset.control.denied", "code", code.toLowerCase(java.util.Locale.ROOT)).increment();
        return new ControlException(status, code, message);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(error);
        }
    }

    private RunConfig parse(String json) {
        try {
            return mapper.readValue(json, RunConfig.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Stored run configuration is unreadable", error);
        }
    }

    /** What the operator asked for. Nulls take defaults; validation happens server-side. */
    public record RunRequest(RunKind kind, Integer sites, Integer assetsPerSite, List<String> metrics, Integer ratePerAsset,
            Integer durationSeconds, String seed) {
    }

    public record Armed(String runId, String seed, String confirmToken, Instant armedUntil, String namespace, String environment,
            int baseRate, String expectedDigest) {
    }

    public record Preview(String seed, RunConfig config, String digest, List<String> lines) {
    }

    /** In-memory state of a run this pod is executing. */
    public static final class ActiveRun {
        final String runId;
        final RunKind kind;
        final String seed;
        final RunConfig config;
        final RunScript script;
        final Instant startedAt;
        final long startedNanos;
        final SequenceDigest digest;
        final org.box1.asset.ingest.TokenBucket bucket;
        volatile long emitted;
        volatile long rejected;
        volatile Emission.Injection injection;
        volatile boolean cancelled;
        ScheduledFuture<?> future;

        ActiveRun(String runId, RunKind kind, String seed, RunConfig config, RunScript script, Instant startedAt, long startedNanos,
                SequenceDigest digest, org.box1.asset.ingest.TokenBucket bucket) {
            this.bucket = bucket;
            this.runId = runId;
            this.kind = kind;
            this.seed = seed;
            this.config = config;
            this.script = script;
            this.startedAt = startedAt;
            this.startedNanos = startedNanos;
            this.digest = digest;
        }

        void cancel() {
            cancelled = true;
            if (future != null) {
                future.cancel(false);
            }
        }

        public Map<String, Object> view() {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("runId", runId);
            view.put("kind", kind);
            view.put("seed", seed);
            view.put("emitted", emitted);
            view.put("rejected", rejected);
            Emission.Injection current = injection;
            view.put("currentInjection", current == null ? null : current.describe());
            return view;
        }
    }
}
