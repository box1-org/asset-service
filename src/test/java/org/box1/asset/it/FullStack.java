package org.box1.asset.it;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import io.quarkus.test.junit.QuarkusTestProfile;

/**
 * The containers suite: real PostgreSQL and Kafka (run15-pg on 15432, run15-kafka on 19092 by default), migrations,
 * scheduler and consumer on, and an open chaos gate for namespace "run15-local".
 */
public class FullStack implements QuarkusTestProfile {
    static final String NAMESPACE = "run15-local";

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> config = new HashMap<>();
        config.put("quarkus.flyway.migrate-at-start", "true");
        config.put("quarkus.flyway.clean-at-start", "true");
        config.put("quarkus.flyway.clean-disabled", "false");
        config.put("quarkus.scheduler.enabled", "true");
        config.put("box1.readiness.database", "true");
        config.put("mp.messaging.incoming.telemetry-in.enabled", "true");
        config.put("mp.messaging.incoming.telemetry-in.group.id", "asset-service-it-" + UUID.randomUUID());
        config.put("box1.outbox.interval", "200ms");
        config.put("box1.gauges.interval", "1s");
        config.put("box1.control.heartbeat", "1s");
        config.put("box1.control.reaper", "1s");
        config.put("asset.control.stale-heartbeat", "PT5S");
        config.put("asset.control.arm-lifetime", "PT3S");
        config.put("asset.control.ttl-grace", "PT2S");
        config.put("asset.chaos.enabled", "true");
        config.put("asset.chaos.namespace-allowlist", NAMESPACE);
        config.put("asset.chaos.pod-namespace", NAMESPACE);
        config.put("asset.chaos.environment", "local-test");
        config.putAll(extra());
        return config;
    }

    protected Map<String, String> extra() {
        return Map.of();
    }

    /** Chaos flag on, but this pod's namespace is not on the allowlist. */
    public static class NotAllowlisted extends FullStack {
        @Override
        protected Map<String, String> extra() {
            return Map.of("asset.chaos.pod-namespace", "asset-service-prod");
        }
    }

    /** Chaos flag off, namespace allowlisted. */
    public static class Disabled extends FullStack {
        @Override
        protected Map<String, String> extra() {
            return Map.of("asset.chaos.enabled", "false");
        }
    }
}
