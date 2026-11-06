package org.box1.asset.api;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerRequestFilter;

/**
 * Cross-site request forgery guard for the session-cookie control API (D-002). A state-changing control call must
 * carry {@code X-Box1-Console: 1} and a JSON content type. A cross-site form cannot set either without a CORS preflight,
 * and the CORS policy only admits the Developer Hub origin, without credentials.
 */
public class ConsoleHeaderFilter {
    static final String HEADER = "X-Box1-Console";

    @ServerRequestFilter
    public Response check(ContainerRequestContext request) {
        String path = request.getUriInfo().getPath();
        if (!path.startsWith("/api/v1/control/") || !"POST".equals(request.getMethod())) {
            return null;
        }
        MediaType type = request.getMediaType();
        if (!"1".equals(request.getHeaderString(HEADER)) || type == null || !type.isCompatible(MediaType.APPLICATION_JSON_TYPE)) {
            return Problem.response(403, "Forbidden", "CONSOLE_HEADER_REQUIRED",
                    "Control actions need the X-Box1-Console header and a JSON body");
        }
        return null;
    }
}
