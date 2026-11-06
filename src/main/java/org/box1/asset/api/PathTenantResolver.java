package org.box1.asset.api;

import io.quarkus.oidc.TenantResolver;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Chooses the OIDC tenant by path alone (D-002). Without this, a console session cookie on a request selects the
 * console tenant even on the bearer-only data API, so a browser session could authenticate ingestion and reads.
 */
@ApplicationScoped
public class PathTenantResolver implements TenantResolver {
    static final String CONSOLE = "console";

    @Override
    public String resolve(RoutingContext context) {
        String path = context.normalizedPath();
        if (isConsole(path)) {
            return CONSOLE;
        }
        return "Default";
    }

    /** Broad on purpose: any path beginning with the console or control prefix uses the browser tenant (SEC-6). */
    static boolean isConsole(String path) {
        return path.startsWith("/console") || path.startsWith("/api/v1/control");
    }
}
