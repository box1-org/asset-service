package org.box1.asset.api;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.quarkus.security.Authenticated;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.box1.asset.config.AssetConfig;
import org.box1.asset.ingest.IngestionPipeline;
import org.box1.asset.ingest.Source;
import org.box1.asset.store.ReadingView;
import org.box1.asset.store.TelemetryStore;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;

@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
public class ReadingsResource {
    static final Duration MAX_WINDOW = Duration.ofHours(24);
    private final IngestionPipeline pipeline;
    private final TelemetryStore store;
    private final Clock clock;
    private final Duration ackTimeout;

    public ReadingsResource(IngestionPipeline pipeline, TelemetryStore store, Clock clock, AssetConfig config) {
        this.pipeline = pipeline;
        this.store = store;
        this.clock = clock;
        this.ackTimeout = config.ingest().ackTimeout();
    }

    @POST
    @Path("/telemetry/readings")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed("telemetry-writer")
    @SecurityRequirement(name = "bearer", scopes = "telemetry-writer")
    @Operation(summary = "Ingest one reading; 202 after Kafka acknowledges it. Retrying a 503 is safe.")
    public Response ingest(byte[] body, @HeaderParam("X-Correlation-ID") String correlationId) {
        String correlation = correlationId != null && correlationId.matches("[A-Za-z0-9:_.-]{1,128}") ? correlationId : null;
        IngestionPipeline.Submission submission = pipeline.submitRaw(body, Source.API, correlation);
        try {
            submission.acked().get(ackTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            return Response.fromResponse(Problem.response(503, "Not confirmed", "ACK_TIMEOUT",
                    "Kafka did not acknowledge in time; the reading may still arrive. Retrying is safe.")).header("Retry-After", 2).build();
        } catch (ExecutionException error) {
            return Response.fromResponse(Problem.response(503, "Not delivered", "DELIVERY_FAILED",
                    "The reading was not delivered to Kafka. Retrying is safe.")).header("Retry-After", 2).build();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return Problem.response(503, "Interrupted", "INTERRUPTED", "Retrying is safe.");
        }
        return Response.accepted(Map.of("eventId", submission.eventId().toString(), "status", "PUBLISHED",
                "traceparent", submission.traceparent() == null ? "" : submission.traceparent())).build();
    }

    @GET
    @Path("/assets/{assetId}/readings")
    @Authenticated
    @SecurityRequirement(name = "bearer")
    @Operation(summary = "Readings for one asset in a time window, ordered by measuredAt then metricName, keyset-paginated")
    public Page<ReadingView> readings(@PathParam("assetId") String assetId, @QueryParam("from") String from, @QueryParam("to") String to,
            @QueryParam("metric") String metric, @QueryParam("limit") Integer limit, @QueryParam("cursor") String cursor) {
        if (!assetId.matches("asset_[A-Za-z0-9_-]{1,64}")) {
            throw new InvalidRequestException("INVALID_ID", "assetId must match asset_[A-Za-z0-9_-]{1,64}");
        }
        if (metric != null && !metric.matches("[a-z][a-z0-9_]{0,40}")) {
            throw new InvalidRequestException("INVALID_METRIC", "metric is not valid");
        }
        Instant end = to == null ? clock.instant() : instant("to", to);
        Instant start = from == null ? end.minus(Duration.ofHours(1)) : instant("from", from);
        if (!start.isBefore(end) || Duration.between(start, end).compareTo(MAX_WINDOW) > 0) {
            throw new InvalidRequestException("INVALID_WINDOW", "from must be before to, and the window at most 24 hours");
        }
        int size = limit(limit, 100, 500);
        Cursors.Position after = cursor == null ? null : Cursors.decode(cursor);
        List<ReadingView> items = store.readings(assetId, start, end, metric, after == null ? null : after.time(),
                after == null ? null : after.key(), size + 1);
        return page(items, size, r -> Cursors.encode(r.measuredAt(), r.metricName()));
    }

    @GET
    @Path("/alerts")
    @Authenticated
    @SecurityRequirement(name = "bearer")
    @Operation(summary = "Alert episodes, newest first, keyset-paginated")
    public Page<org.box1.asset.store.AlertView> alerts(@QueryParam("status") String status, @QueryParam("assetId") String assetId,
            @QueryParam("limit") Integer limit, @QueryParam("cursor") String cursor) {
        if (status != null && !List.of("ACTIVE", "CLEARED").contains(status)) {
            throw new InvalidRequestException("INVALID_STATUS", "status must be ACTIVE or CLEARED");
        }
        if (assetId != null && !assetId.matches("asset_[A-Za-z0-9_-]{1,64}")) {
            throw new InvalidRequestException("INVALID_ID", "assetId must match asset_[A-Za-z0-9_-]{1,64}");
        }
        int size = limit(limit, 50, 200);
        Cursors.Position before = cursor == null ? null : Cursors.decode(cursor);
        List<org.box1.asset.store.AlertView> items = store.alerts(status, assetId, before == null ? null : before.time(),
                before == null ? null : before.key(), size + 1);
        return page(items, size, a -> Cursors.encode(a.activatedAt(), a.alertId()));
    }

    private static <T> Page<T> page(List<T> items, int size, java.util.function.Function<T, String> cursorOf) {
        if (items.size() > size) {
            List<T> trimmed = items.subList(0, size);
            return new Page<>(List.copyOf(trimmed), cursorOf.apply(trimmed.get(size - 1)));
        }
        return new Page<>(items, null);
    }

    private static int limit(Integer limit, int fallback, int max) {
        int value = limit == null ? fallback : limit;
        if (value < 1 || value > max) {
            throw new InvalidRequestException("INVALID_LIMIT", "limit must be between 1 and " + max);
        }
        return value;
    }

    private static Instant instant(String name, String text) {
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException error) {
            throw new InvalidRequestException("INVALID_TIME", name + " must be an RFC 3339 UTC timestamp");
        }
    }

    public record Page<T>(List<T> items, String nextCursor) {
    }
}
