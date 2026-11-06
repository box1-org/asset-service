package org.box1.asset.api;


import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.box1.asset.config.AssetConfig;
import org.box1.asset.config.Limits;
import org.box1.asset.control.ChaosEntry;
import org.box1.asset.control.ChaosGate;
import org.box1.asset.control.ControlException;
import org.box1.asset.control.RunManager;
import org.box1.asset.control.RunRecord;
import org.box1.asset.control.RunStore;
import org.box1.asset.domain.MetricCatalog;
import org.box1.asset.ops.Readouts;
import org.eclipse.microprofile.openapi.annotations.Operation;

/**
 * The console's only API. Every call needs the chaos-operator role; state changes also need the console header
 * ({@link ConsoleHeaderFilter}). The actor is always the authenticated principal, never a request field.
 */
@Path("/api/v1/control")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("chaos-operator")
public class ControlResource {
    private final RunManager manager;
    private final RunStore runs;
    private final ChaosGate gate;
    private final MetricCatalog catalog;
    private final AssetConfig config;
    private final Readouts readouts;
    private final SecurityIdentity identity;

    public ControlResource(RunManager manager, RunStore runs, ChaosGate gate, MetricCatalog catalog, AssetConfig config,
            Readouts readouts, SecurityIdentity identity) {
        this.manager = manager;
        this.runs = runs;
        this.gate = gate;
        this.catalog = catalog;
        this.config = config;
        this.readouts = readouts;
        this.identity = identity;
    }

    @GET
    @Path("/limits")
    @Operation(summary = "Effective limits and compiled-in maximums; the page renders these and enforces nothing itself")
    public Map<String, Object> limits() {
        AssetConfig.Control control = config.control();
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("maxSites", control.maxSites());
        limits.put("maxAssetsPerSite", control.maxAssetsPerSite());
        limits.put("maxRatePerAsset", control.maxRatePerAsset());
        limits.put("maxRunRatePerSecond", control.maxRunRatePerSecond());
        limits.put("maxDurationSeconds", control.maxDuration().toSeconds());
        limits.put("ttlGraceSeconds", control.ttlGrace().toSeconds());
        limits.put("maxConcurrentRuns", control.maxConcurrentRuns());
        limits.put("armLifetimeSeconds", control.armLifetime().toSeconds());
        limits.put("ingestMaxRatePerSecond", config.ingest().maxRatePerSecond());
        limits.put("producerQueueCapacity", config.ingest().queueCapacity());
        limits.put("maxPauseSeconds", config.chaos().maxPause().toSeconds());
        limits.put("maxBurstMultiplier", config.chaos().maxBurstMultiplier());
        limits.put("metrics", catalog.names());
        limits.put("hardMaximums", Map.of("runRate", Limits.MAX_RUN_RATE, "durationSeconds", Limits.MAX_DURATION.toSeconds(),
                "concurrentRuns", Limits.MAX_CONCURRENT_RUNS, "ingestRate", Limits.MAX_INGEST_RATE));
        return limits;
    }

    @GET
    @Path("/catalog")
    @Operation(summary = "The telemetry-only chaos catalog with its bounds")
    public List<Map<String, String>> catalog() {
        return Arrays.stream(ChaosEntry.values())
                .map(e -> Map.of("entry", e.name(), "description", e.description(), "bounds", e.bounds())).toList();
    }

    @GET
    @Path("/state")
    @Operation(summary = "Gate, producer, active runs and readouts, with the time the server confirmed them")
    public Map<String, Object> state() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("confirmedAt", runs.now());
        state.put("namespace", gate.namespace());
        state.put("environment", gate.environment());
        ChaosGate.Decision decision = gate.decide();
        state.put("chaos", Map.of("enabled", gate.enabled(), "allowlisted", gate.allowlisted(), "open", decision.open(),
                "code", decision.code(), "message", decision.message()));
        state.put("actor", actor());
        state.put("instance", manager.owner());
        state.put("openRuns", runs.open().stream().map(r -> view(r, manager.active().get(r.runId()))).toList());
        state.put("readouts", readouts.current());
        return state;
    }

    @POST
    @Path("/preview")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "The first readings a seed and configuration would produce; publishes nothing")
    public RunManager.Preview preview(RunManager.RunRequest request, @QueryParam("limit") Integer limit) {
        if (request == null || request.seed() == null) {
            throw new ControlException(422, "INVALID_SEED", "Preview needs a seed");
        }
        return manager.preview(actor(), request, limit == null ? 20 : Math.max(1, Math.min(limit, 200)));
    }

    @POST
    @Path("/runs")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Arm a run. Returns a confirmation token valid for the arm window; nothing runs yet.")
    public Response arm(RunManager.RunRequest request) {
        return Response.status(201).entity(manager.arm(actor(), request)).build();
    }

    @POST
    @Path("/runs/{runId}/start")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Start an armed run; needs its confirmation token and the namespace typed exactly")
    public Map<String, Object> start(@PathParam("runId") String runId, StartRequest request) {
        if (request == null) {
            throw new ControlException(422, "INVALID_REQUEST", "confirmToken and confirmNamespace are required");
        }
        return view(manager.start(actor(), runId(runId), request.confirmToken(), request.confirmNamespace()), null);
    }

    @POST
    @Path("/runs/{runId}/stop")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Stop one run")
    public Map<String, Object> stop(@PathParam("runId") String runId) {
        return Map.of("runId", runId, "stopped", manager.stop(actor(), runId(runId)));
    }

    @POST
    @Path("/stop-all")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Stop every run and resume the producer")
    public Map<String, Object> stopAll() {
        return Map.of("stopped", manager.stopAll(actor()));
    }

    @POST
    @Path("/reset")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Stop every run and clear service-local fault state; deletes no data")
    public Map<String, Object> reset() {
        return Map.of("stopped", manager.reset(actor()));
    }

    @GET
    @Path("/runs")
    @Operation(summary = "Recent runs, newest first")
    public List<Map<String, Object>> recent() {
        return runs.recent(25).stream().map(r -> view(r, manager.active().get(r.runId()))).toList();
    }

    @GET
    @Path("/runs/{runId}")
    @Operation(summary = "One run with its full audit trail")
    public Map<String, Object> run(@PathParam("runId") String runId) {
        RunRecord record = runs.find(runId(runId)).orElseThrow(() -> new ControlException(404, "RUN_NOT_FOUND", "No run " + runId));
        Map<String, Object> view = view(record, manager.active().get(runId));
        view.put("audit", runs.auditFor(runId));
        return view;
    }

    @GET
    @Path("/audit")
    @Operation(summary = "Most recent audit entries across runs, newest first")
    public Object audit(@QueryParam("limit") Integer limit) {
        return runs.recentAudit(limit == null ? 50 : Math.max(1, Math.min(limit, 200)));
    }

    private String actor() {
        return identity.getPrincipal().getName();
    }

    private static String runId(String runId) {
        if (!runId.matches("run_[a-f0-9]{20}")) {
            throw new ControlException(404, "RUN_NOT_FOUND", "No such run");
        }
        return runId;
    }

    private static Map<String, Object> view(RunRecord record, RunManager.ActiveRun local) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("runId", record.runId());
        view.put("kind", record.kind());
        view.put("state", record.state());
        view.put("actor", record.actor());
        view.put("seed", record.seed());
        view.put("config", record.config());
        view.put("namespace", record.namespace());
        view.put("createdAt", record.createdAt());
        view.put("armedUntil", record.armedUntil());
        view.put("startedAt", record.startedAt());
        view.put("deadline", record.deadline());
        view.put("ttlAt", record.ttlAt());
        view.put("stoppedAt", record.stoppedAt());
        view.put("stopReason", record.stopReason());
        view.put("heartbeatAt", record.heartbeatAt());
        view.put("sequenceDigest", record.sequenceDigest());
        view.put("emitted", local == null ? record.emitted() : local.view().get("emitted"));
        view.put("rejected", local == null ? record.rejected() : local.view().get("rejected"));
        view.put("executingHere", local != null);
        view.put("currentInjection", local == null ? null : local.view().get("currentInjection"));
        return view;
    }

    public record StartRequest(String confirmToken, String confirmNamespace) {
    }

}
