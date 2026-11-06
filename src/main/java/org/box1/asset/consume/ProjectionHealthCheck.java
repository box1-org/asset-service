package org.box1.asset.consume;

import java.time.Duration;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * Readiness DOWN when one record has been retried for longer than the limit (review MSG-2). The record is not
 * dead-lettered: a stalled projection should be seen and fixed, not silently skipped.
 */
@Readiness
@ApplicationScoped
public class ProjectionHealthCheck implements HealthCheck {
    private final ReadingConsumer consumer;
    private final Duration limit;

    public ProjectionHealthCheck(ReadingConsumer consumer, @ConfigProperty(name = "box1.projection.stall-limit", defaultValue = "PT10M")
            Duration limit) {
        this.consumer = consumer;
        this.limit = limit;
    }

    @Override
    public HealthCheckResponse call() {
        Duration retrying = consumer.retrying();
        return HealthCheckResponse.named("projection").status(retrying.compareTo(limit) <= 0)
                .withData("retryingSeconds", retrying.toSeconds()).build();
    }
}
