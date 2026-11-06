package org.box1.asset.it;

/** Tests tagged with this need PostgreSQL and Kafka; excluded by default, run with -Pcontainers. */
public final class ContainersTag {
    public static final String NAME = "containers";

    private ContainersTag() {
    }
}
