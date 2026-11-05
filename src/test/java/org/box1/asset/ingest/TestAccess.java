package org.box1.asset.ingest;

/** Drives the publisher's sender synchronously in tests. */
public final class TestAccess {
    private TestAccess() {
    }

    public static void drainOnce(ReadingPublisher publisher) {
        publisher.drainOnce();
    }
}
