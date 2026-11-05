package org.box1.asset.api;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import com.fasterxml.jackson.core.JsonProcessingException;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import org.box1.asset.control.ControlException;
import org.box1.asset.domain.RejectedReadingException;
import org.box1.asset.store.StoreException;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/** Every error from this service's business API is application/problem+json. Driver text never reaches a client. */
public class ProblemMappers {
    private static final Logger LOG = Logger.getLogger(ProblemMappers.class);

    @ServerExceptionMapper
    public Response rejected(RejectedReadingException error) {
        Response response = switch (error.reason()) {
            case OVERSIZE -> Problem.response(413, "Payload too large", error.reason().name(), error.getMessage());
            case RATE_LIMITED -> withRetry(Problem.response(429, "Rate limited", error.reason().name(), error.getMessage()), 1);
            case BACKPRESSURE -> withRetry(Problem.response(503, "Backpressure", error.reason().name(), error.getMessage()), 2);
            default -> Problem.response(400, "Invalid reading", error.reason().name(), error.getMessage());
        };
        return response;
    }

    @ServerExceptionMapper
    public Response control(ControlException error) {
        return Problem.response(error.status(), "Control action refused", error.code(), error.getMessage());
    }

    @ServerExceptionMapper
    public Response invalid(InvalidRequestException error) {
        return Problem.response(400, "Invalid request", error.code(), error.getMessage());
    }

    @ServerExceptionMapper
    public Response store(StoreException error) {
        LOG.warnf("Store unavailable: %s", error.getMessage());
        return withRetry(Problem.response(503, "Storage unavailable", "STORE_UNAVAILABLE", "The reading store is unavailable; retry later"), 5);
    }

    @ServerExceptionMapper
    public Response json(JsonProcessingException error) {
        return Problem.response(400, "Invalid JSON", "MALFORMED", "The body is not valid JSON for this operation");
    }

    @ServerExceptionMapper(priority = jakarta.ws.rs.Priorities.USER + 100)
    public Response unauthorized(UnauthorizedException error) {
        return Problem.response(401, "Unauthorized", "UNAUTHENTICATED", "Authentication is required");
    }

    @ServerExceptionMapper(priority = jakarta.ws.rs.Priorities.USER + 100)
    public Response forbidden(ForbiddenException error) {
        return Problem.response(403, "Forbidden", "FORBIDDEN", "The token lacks the role this operation needs");
    }

    @ServerExceptionMapper
    public Response notFound(NotFoundException error) {
        return Problem.response(404, "Not found", "NOT_FOUND", "No such resource");
    }

    @ServerExceptionMapper(priority = jakarta.ws.rs.Priorities.USER + 200)
    public Response web(WebApplicationException error) {
        int status = error.getResponse().getStatus();
        return Problem.response(status, "Request failed", "HTTP_" + status, "The request could not be processed");
    }

    private static Response withRetry(Response response, int seconds) {
        return Response.fromResponse(response).header("Retry-After", seconds).build();
    }
}
