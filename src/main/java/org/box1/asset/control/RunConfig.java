package org.box1.asset.control;

import java.util.List;

/** What the operator asked for. Validated against limits on arm and again on start. */
public record RunConfig(RunKind kind, int sites, int assetsPerSite, List<String> metrics, int ratePerAsset,
        int durationSeconds) {

    /** Readings per second before chaos. */
    public int baseRate() {
        return sites * assetsPerSite * metrics.size() * ratePerAsset;
    }

    /** Every siteId this run emits, in order. */
    public List<String> siteIds() {
        return java.util.stream.IntStream.range(0, sites).mapToObj(RunConfig::siteId).toList();
    }

    /** The siteIds this run would emit that are reserved for real edge sites (box1-docs#128); empty when none. */
    public List<String> reservedSiteIds(java.util.Collection<String> reserved) {
        return siteIds().stream().filter(reserved::contains).toList();
    }

    public static String siteId(int site) {
        return String.format("site_load_%02d", site + 1);
    }

    public static String assetId(int site, int asset) {
        return String.format("asset_load_%02d_%02d", site + 1, asset + 1);
    }
}
