package org.box1.asset.ingest;

/** Where a reading entered the pipeline. Used as a metric tag and in the envelope correlation ID. */
public enum Source {
    API, LOAD, CHAOS;

    public String tag() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
