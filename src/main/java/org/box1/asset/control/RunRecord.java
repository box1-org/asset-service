package org.box1.asset.control;

import java.time.Instant;

/** A row of control_run. */
public record RunRecord(String runId, RunKind kind, String actor, String seed, String config, String state, String namespace,
        Instant createdAt, Instant armedUntil, Instant startedAt, Instant deadline, Instant ttlAt, Instant stoppedAt,
        String stopReason, String owner, Instant heartbeatAt, String sequenceDigest, long emitted, long rejected) {
}
