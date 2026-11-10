package org.box1.asset.site;

import java.time.Instant;

/**
 * A site's latest stored status. {@code receivedAt} is asset-service's clock when it stored the report (the contract's
 * {@code lastSeenAt}); {@code lastReadingAt} is the newest stored reading's measuredAt, or null when there is none.
 */
public record StoredSite(String siteId, SiteReport report, Instant receivedAt, String clientId, Instant lastReadingAt) {
}
