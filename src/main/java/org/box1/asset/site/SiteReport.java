package org.box1.asset.site;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * A validated {@code SiteStatusReport} (box1-ops-contracts openapi/asset-service.yaml). Digests are null when the site
 * does not know them; {@code appVersions} is never null.
 */
public record SiteReport(Instant reportedAt, long bufferDepth, long siteEventBacklog, long bufferOldestAgeSeconds,
        long readingsDropped, String bootedImageDigest, String stagedImageDigest, String rollbackImageDigest,
        String greenbootStatus, String bundleDigest, Map<String, String> appVersions) {

    public SiteReport {
        // Sorted, so responses and comparisons do not depend on the order the site sent.
        appVersions = Collections.unmodifiableSortedMap(new TreeMap<>(appVersions));
    }
}
