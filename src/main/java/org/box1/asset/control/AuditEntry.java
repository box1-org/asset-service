package org.box1.asset.control;

import java.time.Instant;

/** A row of control_audit. */
public record AuditEntry(long id, String runId, Instant at, String actor, String action, String detail) {
}
