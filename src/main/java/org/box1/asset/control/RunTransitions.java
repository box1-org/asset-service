package org.box1.asset.control;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.box1.asset.config.AssetConfig;

/**
 * Database transitions that must be atomic with their audit row and serialized under the control lock. A separate bean
 * so the transaction interceptor applies when RunManager calls it.
 */
@ApplicationScoped
public class RunTransitions {
    private final RunStore runs;
    private final ChaosGate gate;
    private final AssetConfig.Control limits;

    public RunTransitions(RunStore runs, ChaosGate gate, AssetConfig config) {
        this.runs = runs;
        this.gate = gate;
        this.limits = config.control();
    }

    /** Records the armed run and its ARM audit row together (review PERS-7, ARCH-9). Caps open arms (SEC-8). */
    @Transactional(dontRollbackOn = ControlException.class)
    public void arm(String runId, RunKind kind, String actor, String seed, String config, String namespace, long armSeconds,
            String confirmHash, String owner, String auditDetail, int maxArmed) {
        runs.lock();
        if (runs.countArmed() >= maxArmed) {
            runs.audit(null, actor, "DENIED", "{\"code\":\"ARM_CAP\"}");
            throw new ControlException(409, "ARM_CAP", "At most " + maxArmed + " runs may be armed at once; start or disarm one");
        }
        runs.insertArmed(runId, kind, actor, seed, config, namespace, armSeconds, confirmHash, owner);
        runs.audit(runId, actor, "ARM", auditDetail);
    }

    /** Refusals keep their DENIED audit row: a ControlException commits what was written before it. */
    @Transactional(dontRollbackOn = ControlException.class)
    public RunRecord start(String actor, String runId, String confirmToken, String confirmNamespace, String owner, long durationSeconds,
            long ttlSeconds) {
        runs.lock();
        RunRecord run = runs.find(runId).orElseThrow(() -> new ControlException(404, "RUN_NOT_FOUND", "No run " + runId));
        if (!"ARMED".equals(run.state())) {
            throw new ControlException(409, "NOT_ARMED", "Run " + runId + " is " + run.state() + ", not ARMED");
        }
        if (runs.armExpired(runId)) {
            throw new ControlException(410, "ARM_EXPIRED", "The arm window expired; arm the run again");
        }
        String expected = runs.confirmHash(runId).orElse("");
        if (confirmToken == null || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                hash(confirmToken).getBytes(StandardCharsets.US_ASCII))) {
            runs.audit(runId, actor, "DENIED", "{\"code\":\"CONFIRM_MISMATCH\"}");
            throw new ControlException(403, "CONFIRM_MISMATCH", "The confirmation token does not match this run");
        }
        if (!gate.namespace().equals(confirmNamespace)) {
            throw refuse(runId, actor, 422, "NAMESPACE_MISMATCH", "Type the namespace exactly: " + gate.namespace());
        }
        if (run.kind() == RunKind.CHAOS && !gate.decide().open()) {
            throw refuse(runId, actor, 403, gate.decide().code(), gate.decide().message());
        }
        if (runs.countRunning() >= limits.maxConcurrentRuns()) {
            throw refuse(runId, actor, 409, "CONCURRENCY_CAP", "At most " + limits.maxConcurrentRuns() + " runs may be active");
        }
        if (!runs.markRunning(runId, durationSeconds, ttlSeconds, owner)) {
            throw new ControlException(409, "NOT_ARMED", "Run " + runId + " changed state");
        }
        runs.audit(runId, actor, "START", "{\"durationSeconds\":" + durationSeconds + ",\"ttlSeconds\":" + ttlSeconds + "}");
        return runs.find(runId).orElseThrow();
    }

    @Transactional
    public boolean finish(String runId, String state, String reason, String actor, String action, String detail) {
        boolean changed = runs.finish(runId, state, reason);
        if (changed) {
            runs.audit(runId, actor, action, detail);
        }
        return changed;
    }

    @Transactional
    public List<String> stopAll(String actor, String action) {
        runs.lock();
        List<String> stopped = new ArrayList<>();
        for (RunRecord run : runs.open()) {
            if (runs.finish(run.runId(), "STOPPED", action + " by " + actor)) {
                stopped.add(run.runId());
                runs.audit(run.runId(), actor, action, "{}");
            }
        }
        runs.audit(null, actor, action, "{\"stopped\":" + stopped.size() + "}");
        return stopped;
    }

    /** One reaper pass. Returns the run IDs it ended. */
    @Transactional
    public List<String> reap(long staleSeconds) {
        runs.lock();
        List<String> ended = new ArrayList<>();
        for (RunRecord run : runs.staleHeartbeats(staleSeconds)) {
            if (runs.finish(run.runId(), "ABORTED", "owner stopped heartbeating (pod restart or crash); not resumed")) {
                runs.audit(run.runId(), "system", "ABORTED_RESTART", "{\"owner\":\"" + run.owner() + "\"}");
                ended.add(run.runId());
            }
        }
        for (RunRecord run : runs.expiredArms()) {
            if (runs.finish(run.runId(), "STOPPED", "arm window expired without start")) {
                runs.audit(run.runId(), "system", "ARM_EXPIRED", "{}");
                ended.add(run.runId());
            }
        }
        for (RunRecord run : runs.pastTtl()) {
            if (runs.finish(run.runId(), "EXPIRED", "TTL reached")) {
                runs.audit(run.runId(), "system", "TTL_EXPIRED", "{}");
                ended.add(run.runId());
            }
        }
        return ended;
    }

    /** Every start refusal is audited (review PERS-8). */
    private ControlException refuse(String runId, String actor, int status, String code, String message) {
        runs.audit(runId, actor, "DENIED", "{\"code\":\"" + code + "\"}");
        return new ControlException(status, code, message);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
