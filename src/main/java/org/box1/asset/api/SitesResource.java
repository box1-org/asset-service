package org.box1.asset.api;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.json.JsonString;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.box1.asset.config.AssetConfig;
import org.box1.asset.site.SiteReport;
import org.box1.asset.site.SiteStates;
import org.box1.asset.site.StoredSite;
import org.box1.asset.store.SiteStore;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirementsSet;
import org.jboss.logging.Logger;

/**
 * Edge site status (box1-docs#130, box1-ops-contracts SITE.md). Each site's own client reports a heartbeat; readers
 * see every site's latest status with its state derived at read time from configured thresholds.
 */
@Path("/api/v1/sites")
@Produces(MediaType.APPLICATION_JSON)
public class SitesResource {
    static final String SITE_CLAIM = "siteId";
    private static final Logger LOG = Logger.getLogger(SitesResource.class);

    private final SiteStore store;
    private final ClientAccess access;
    private final SecurityIdentity identity;
    private final Clock clock;
    private final SiteStates.Thresholds thresholds;

    public SitesResource(SiteStore store, ClientAccess access, SecurityIdentity identity, Clock clock, AssetConfig config) {
        this.store = store;
        this.access = access;
        this.identity = identity;
        this.clock = clock;
        this.thresholds = SiteStates.Thresholds.of(config);
    }

    @GET
    @RolesAllowed({"telemetry-reader", "telemetry-writer", "chaos-operator"})
    @SecurityRequirementsSet(@SecurityRequirement(name = "bearer", scopes = "telemetry-reader"))
    @SecurityRequirementsSet(@SecurityRequirement(name = "bearer", scopes = "telemetry-writer"))
    @SecurityRequirementsSet(@SecurityRequirement(name = "bearer", scopes = "chaos-operator"))
    @Operation(operationId = "listSites", summary = "Every site that has reported a status, ordered by siteId, with its state derived at read time")
    @APIResponse(responseCode = "200", description = "Every site that has reported a status, ordered by siteId",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = SiteStatusView.Page.class)))
    @APIResponse(responseCode = "401", description = "No valid token")
    @APIResponse(responseCode = "403", description = "Token lacks a reader role")
    public SiteStatusView.Page list() {
        Instant now = clock.instant();
        return new SiteStatusView.Page(store.list().stream().map(site -> SiteStatusView.of(site, thresholds, now)).toList());
    }

    @GET
    @Path("/{siteId}")
    @RolesAllowed({"telemetry-reader", "telemetry-writer", "chaos-operator"})
    @SecurityRequirementsSet(@SecurityRequirement(name = "bearer", scopes = "telemetry-reader"))
    @SecurityRequirementsSet(@SecurityRequirement(name = "bearer", scopes = "telemetry-writer"))
    @SecurityRequirementsSet(@SecurityRequirement(name = "bearer", scopes = "chaos-operator"))
    @Operation(operationId = "getSite", summary = "The site's latest status and its state derived at read time")
    @APIResponse(responseCode = "200", description = "The site's latest status and its state derived at read time",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = SiteStatusView.class)))
    @APIResponse(responseCode = "401", description = "No valid token")
    @APIResponse(responseCode = "403", description = "Token lacks a reader role")
    @APIResponse(responseCode = "404", description = "No status has been reported for this site",
            content = @Content(mediaType = Problem.MEDIA_TYPE))
    public Response get(@PathParam("siteId") @Parameter(schema = @Schema(pattern = "^site_[A-Za-z0-9_-]{1,64}$")) String siteId) {
        if (!SiteReports.validSiteId(siteId)) {
            return notFound();
        }
        return store.find(siteId).map(site -> Response.ok(SiteStatusView.of(site, thresholds, clock.instant())).build())
                .orElseGet(SitesResource::notFound);
    }

    @PUT
    @Path("/{siteId}/status")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed("site-reporter")
    @Transactional
    @SecurityRequirement(name = "bearer", scopes = "site-reporter")
    @Operation(operationId = "reportSiteStatus", summary = "Heartbeat from an edge site",
            description = "Replaces the site's latest status; the server records the time it received it as lastSeenAt. Only the site's"
                    + " own client may write (token claim siteId equal to the path siteId).")
    @RequestBody(required = true, content = @Content(mediaType = MediaType.APPLICATION_JSON,
            schema = @Schema(implementation = ApiDocumentation.SiteStatusReport.class)))
    @APIResponse(responseCode = "200", description = "Stored; the site's status as readers now see it",
            content = @Content(mediaType = MediaType.APPLICATION_JSON, schema = @Schema(implementation = SiteStatusView.class)))
    @APIResponse(responseCode = "400", description = "Invalid report; code names the rule", content = @Content(mediaType = Problem.MEDIA_TYPE))
    @APIResponse(responseCode = "401", description = "No valid token")
    @APIResponse(responseCode = "403", description = "Token lacks site-reporter, or its siteId claim is another site's",
            content = @Content(mediaType = Problem.MEDIA_TYPE))
    public Response report(@PathParam("siteId") @Parameter(schema = @Schema(pattern = "^site_[A-Za-z0-9_-]{1,64}$")) String siteId,
            @Parameter(hidden = true) byte[] body) {
        // Identity before validation: a token for another site learns nothing about this site's rules or state.
        String client = azp();
        String claim = siteClaim();
        if (client == null || !access.isSiteClient(client) || claim == null || !claim.equals(siteId)) {
            LOG.warnf("Site status write to %s refused for client %s", SiteReports.validSiteId(siteId) ? siteId : "(invalid siteId)",
                    client != null && client.matches("[A-Za-z0-9_.-]{1,64}") ? client : "(unknown)");
            return Problem.response(403, "Forbidden", "SITE_MISMATCH",
                    "Only the site's own client may write its status: the token's siteId claim must equal the path siteId");
        }
        if (!SiteReports.validSiteId(siteId)) {
            throw new InvalidRequestException("INVALID_ID", "siteId must match ^site_[A-Za-z0-9_-]{1,64}$");
        }
        if (body != null && body.length > SiteReports.MAX_BODY_BYTES) {
            throw new InvalidRequestException("OVERSIZE", "The body is over " + SiteReports.MAX_BODY_BYTES + " bytes");
        }
        SiteReport report = SiteReports.parse(body);
        // Millisecond precision, so the stored lastSeenAt and the one returned here are identical.
        Instant received = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        store.upsert(siteId, report, received, client);
        StoredSite stored = store.find(siteId).orElseThrow(() -> new IllegalStateException("site status not visible after upsert"));
        return Response.ok(SiteStatusView.of(stored, thresholds, clock.instant())).build();
    }

    private String azp() {
        return identity.getPrincipal() instanceof JsonWebToken token && token.getClaim("azp") instanceof String azp ? azp : null;
    }

    private String siteClaim() {
        if (!(identity.getPrincipal() instanceof JsonWebToken token)) {
            return null;
        }
        Object value = token.getClaim(SITE_CLAIM);
        if (value instanceof JsonString json) {
            return json.getString();
        }
        return value instanceof String text ? text : null;
    }

    private static Response notFound() {
        return Problem.response(404, "Not found", "SITE_NOT_FOUND", "No status has been reported for this siteId");
    }
}
