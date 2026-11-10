package org.box1.asset.api;

import java.util.Set;
import java.util.stream.Collectors;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.box1.asset.config.AssetConfig;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Which Keycloak clients' tokens asset-service accepts (security review SEC-3, box1-docs#80).
 * <ul>
 * <li>Tokens issued to asset-service itself ({@code azp=asset-service}) keep all their asset-service roles.</li>
 * <li>Tokens issued to a client listed in {@code asset.read-clients} (the operations console) keep only
 * {@code telemetry-reader}, so they can read readings and alerts and nothing else.</li>
 * <li>Tokens issued to a client listed in {@code asset.site-clients} (the edge sites, box1-docs#130) keep only
 * {@code site-reporter}, so they can write their own site's status (SitesResource checks the {@code siteId} claim)
 * and read nothing.</li>
 * <li>Every other {@code azp} is refused with 401, as before. Keycloak adds {@code aud asset-service} to any client's
 * token for a user who holds asset-service roles, so the audience alone is never enough.</li>
 * </ul>
 * Interim: the console's client is public (box1-docs#79), so a realm user could obtain its token directly. That
 * gives them nothing beyond the telemetry-reader role they already hold.
 */
@ApplicationScoped
public class ClientAccess implements SecurityIdentityAugmentor {
    static final String OWN_CLIENT = "asset-service";
    static final String READER_ROLE = "telemetry-reader";
    static final String SITE_ROLE = "site-reporter";

    private final Set<String> readClients;
    private final Set<String> siteClients;

    public ClientAccess(AssetConfig config) {
        this.siteClients = clients(config.siteClients(), Set.of());
        // A client is never both: a site client listed as a reader too stays a site client, the narrower role.
        this.readClients = clients(config.readClients(), siteClients);
    }

    private static Set<String> clients(java.util.Optional<java.util.List<String>> configured, Set<String> excluded) {
        return configured.map(l -> l.stream().map(String::strip).filter(s -> !s.isEmpty()).filter(s -> !OWN_CLIENT.equals(s))
                .filter(s -> !excluded.contains(s)).collect(Collectors.toUnmodifiableSet())).orElse(Set.of());
    }

    /** Whether {@code azp} is an edge site's own client. */
    public boolean isSiteClient(String azp) {
        return azp != null && siteClients.contains(azp);
    }

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity, AuthenticationRequestContext context) {
        if (identity.isAnonymous() || !(identity.getPrincipal() instanceof JsonWebToken token)) {
            return Uni.createFrom().item(identity);
        }
        String azp = token.getClaim("azp");
        if (OWN_CLIENT.equals(azp)) {
            return Uni.createFrom().item(identity);
        }
        if (isSiteClient(azp)) {
            return Uni.createFrom().item(only(identity, SITE_ROLE));
        }
        if (azp != null && readClients.contains(azp)) {
            return Uni.createFrom().item(only(identity, READER_ROLE));
        }
        return Uni.createFrom().failure(new AuthenticationFailedException("token not issued to an accepted client"));
    }

    /** A fresh identity, not a copy: copying would carry the token's other asset-service roles along. */
    private static SecurityIdentity only(SecurityIdentity identity, String role) {
        QuarkusSecurityIdentity.Builder narrowed = QuarkusSecurityIdentity.builder().setPrincipal(identity.getPrincipal())
                .addCredentials(identity.getCredentials()).addAttributes(identity.getAttributes()).setAnonymous(false);
        if (identity.hasRole(role)) {
            narrowed.addRole(role);
        }
        return narrowed.build();
    }
}
