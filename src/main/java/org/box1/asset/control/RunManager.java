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
        runs.insertArmed(runId, runConfig.kind(), actor, seedText, configJson, gate.namespace(), armSeconds, RunTransitions.hash(token), owner);
        String expected = SequenceDigest.of(RunScript.first(seed, runConfig, Instant.EPOCH, catalog, config.ingest().maxBodyBytes(),
                config.chaos().maxBurstMultiplier(), config.chaos().maxPause(), SequenceDigest.LENGTH), Instant.EPOCH);
        runs.audit(runId, actor, "ARM", json(Map.of("seed", seedText, "config", runConfig, "expectedDigest", expected)));
        RunRecord record = runs.find(runId).orElseThrow();
        return new Armed(runId, seedText, token, record.armedUntil(), gate.namespace(), gate.environment(), runConfig.baseRate(), expected);
    }

    /** Starts an armed run after the confirmation token and the typed namespace match. */
    public RunRecord start(String actor, String runId, String confirmToken, String confirmNamespace) {
        RunRecord armed = runs.find(runId).orElseThrow(() -> new ControlException(404, "RUN_NOT_FOUND", "No run " + runId));
        RunConfig runConfig = parse(armed.config());
        long duration = runConfig.durationSeconds();
        long ttl = duration + config.control().ttlGrace().toSeconds();
        RunRecord started = transitions.start(actor, runId, confirmToken, confirmNamespace, owner, duration, ttl);
        launch(started, runConfig);
        return started;
    }

    public boolean stop(String actor, String runId) {
        runs.find(runId).orElseThrow(() -> new ControlException(404, "RUN_NOT_FOUND", "No run " + runId));
        ActiveRun run = active.get(runId);
        if (run != null) {
            return end(run, "STOPPED", "stopped by " + actor, actor, "STOP");
        }
        return transitions.finish(runId, "STOPPED", "stopped by " + actor, actor, "STOP", "{}");
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
        for (ActiveRun run : active.values()) {
            run.cancel();
        }
        active.clear();
        publisher.resume();
        List<String> stopped = transitions.stopAll(actor, action);
        LOG.infof("%s by %s stopped %d runs", action, actor, stopped.size());
        return stopped;
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
        for (ActiveRun run : List.copyOf(active.values())) {
            try {
                if (!runs.heartbeat(run.runId, owner, run.emitted, run.rejected, run.digest.value())) {
                    LOG.infof("Run %s is no longer running in the database; stopping locally", run.runId);
                    run.cancel();
                    active.remove(run.runId);
                }
            } catch (StoreException error) {
                LOG.warnf("Heartbeat for %s failed (%s)", run.runId, error.getMessage());
            }
        }
    }

    @Scheduled(every = "${box1.control.reaper:5s}", delay = 2, delayUnit = TimeUnit.SECONDS,
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void reap() {
        try {
            for (String runId : transitions.reap(config.control().staleHeartbeat().toSeconds())) {
                ActiveRun run = active.remove(runId);
                if (run != null) {
                    run.cancel();
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
                new SequenceDigest(start));
        active.put(run.runId, run);
        run.future = ticker.scheduleAtFixedRate(() -> tick(run), 0, TICK_MILLIS, TimeUnit.MILLISECONDS);
        LOG.infof("Run %s started: %s seed %s, %d readings/s for %d s", run.runId, run.kind, run.seed, runConfig.baseRate(),
                runConfig.durationSeconds());
    }

    private void tick(ActiveRun run) {
        try {
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
            publisher.pause(pause);
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
        run.cancel();
        active.remove(run.runId);
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
        if (runConfig.baseRate() > limits.maxRunRatePerSecond()) {
            throw denied(actor, null, 422, "RATE_CAP", "sites x assetsPerSite x metrics x ratePerAsset = " + runConfig.baseRate()
                    + " readings/s; the cap is " + limits.maxRunRatePerSecond());
        }
        return runConfig;
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
        volatile long emitted;
        volatile long rejected;
        volatile Emission.Injection injection;
        volatile boolean cancelled;
        ScheduledFuture<?> future;

        ActiveRun(String runId, RunKind kind, String seed, RunConfig config, RunScript script, Instant startedAt, long startedNanos,
                SequenceDigest digest) {
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
