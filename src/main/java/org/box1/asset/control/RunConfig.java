package org.box1.asset.control;

import java.util.List;

/** What the operator asked for. Validated against limits on arm and again on start. */
public record RunConfig(RunKind kind, int sites, int assetsPerSite, List<String> metrics, int ratePerAsset,
        int durationSeconds) {

    /** Readings per second before chaos. */
    public int baseRate() {
        return sites * assetsPerSite * metrics.size() * ratePerAsset;
    }

    public static String siteId(int site) {
        return String.format("site_load_%02d", site + 1);
    }

    public static String assetId(int site, int asset) {
        return String.format("asset_load_%02d_%02d", site + 1, asset + 1);
    }
}
