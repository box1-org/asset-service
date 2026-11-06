package org.box1.asset.ops;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/** PostgreSQL is a readiness dependency; Kafka is not (D-010). The flag is runtime so the no-database tests can skip it. */
@Readiness
@ApplicationScoped
public class DatabaseReadinessCheck implements HealthCheck {
    private final DataSource dataSource;
    private final boolean enabled;

    public DatabaseReadinessCheck(DataSource dataSource, @ConfigProperty(name = "box1.readiness.database") boolean enabled) {
        this.dataSource = dataSource;
        this.enabled = enabled;
    }

    @Override
    public HealthCheckResponse call() {
        if (!enabled) {
            return HealthCheckResponse.named("database").up().withData("checked", false).build();
        }
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(2) ? HealthCheckResponse.up("database") : HealthCheckResponse.down("database");
        } catch (SQLException error) {
            return HealthCheckResponse.down("database");
        }
    }
}
