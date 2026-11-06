package org.box1.asset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

import org.box1.asset.consume.Failures;
import org.box1.asset.store.StoreException;
import org.junit.jupiter.api.Test;

/** TEL-DLQ-001: a database outage is transient (hold); a defect or constraint error is not (bounded, then dead-letter). */
class FailuresTest {
    @Test
    void classifiesDatabaseOutagesAsTransientAndDefectsAsPermanent() {
        assertTrue(Failures.isTransient(new StoreException("insert", new SQLException("refused", "08001"))));
        assertTrue(Failures.isTransient(new StoreException("insert", new SQLException("shutdown", "57P01"))));
        assertTrue(Failures.isTransient(new StoreException("insert", new SQLException("deadlock", "40P01"))));
        assertTrue(Failures.isTransient(new StoreException("insert", new SQLException("Acquisition timeout"))));
        assertTrue(Failures.isTransient(new RuntimeException(new SQLTransientConnectionException("x"))));
        assertFalse(Failures.isTransient(new StoreException("insert", new SQLException("unique", "23505"))));
        assertFalse(Failures.isTransient(new IllegalArgumentException("meter tag keys")));
    }
}
