package org.box1.asset.consume;

import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;

/** Decides whether a projection failure can heal by waiting. */
public final class Failures {
    private Failures() {
    }

    /**
     * Connection, resource, operator-intervention and transaction-rollback SQL states, and connection acquisition
     * failures without a state, are transient. Everything else (constraint violations, programming errors) is not.
     */
    public static boolean isTransient(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLTransientException || cause instanceof SQLRecoverableException) {
                return true;
            }
            if (cause instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (state == null || state.startsWith("08") || state.startsWith("53") || state.startsWith("57")
                        || state.startsWith("40")) {
                    return true;
                }
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }
}
