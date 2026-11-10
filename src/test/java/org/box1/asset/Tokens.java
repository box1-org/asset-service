package org.box1.asset;

import java.util.List;
import java.util.Map;

import io.smallrye.jwt.build.Jwt;

/** Tokens signed with the OIDC WireMock test key (kid 1). Roles go where the test says, so claim-path rules are tested. */
public final class Tokens {
    private Tokens() {
    }

    public static String clientRoles(String user, String... roles) {
        return base(user).claim("resource_access", Map.of("asset-service", Map.of("roles", List.of(roles)))).jws().keyId("1")
                .sign("privateKey.jwk");
    }

    public static String topLevelGroups(String user, String... groups) {
        return base(user).groups(java.util.Set.of(groups)).claim("realm_access", Map.of("roles", List.of(groups))).jws().keyId("1")
                .sign("privateKey.jwk");
    }

    /** What Keycloak issues to another client for a user who holds asset-service roles: aud includes asset-service. */
    public static String otherClient(String user, String... roles) {
        return Jwt.preferredUserName(user).subject(user).issuer("https://server.example.com").audience(java.util.Set.of("asset-service", "openshell-cli"))
                .claim("azp", "openshell-cli").claim("resource_access", Map.of("asset-service", Map.of("roles", List.of(roles))))
                .expiresIn(300).jws().keyId("1").sign("privateKey.jwk");
    }

    /** A token issued to the named client (azp) with aud asset-service, as Keycloak issues with an audience mapper. */
    public static String issuedTo(String azp, String user, String... roles) {
        var claims = Jwt.preferredUserName(user).subject(user).issuer("https://server.example.com")
                .audience(azp == null ? java.util.Set.of("asset-service") : java.util.Set.of("asset-service", azp)).claim("resource_access", Map.of("asset-service", Map.of("roles", List.of(roles))))
                .expiresIn(300);
        if (azp != null) {
            claims.claim("azp", azp);
        }
        return claims.jws().keyId("1").sign("privateKey.jwk");
    }

    /**
     * What Keycloak issues to an edge site's client (box1-docs#130): azp the client, aud asset-service, the client roles,
     * and a hardcoded siteId claim (omitted when null).
     */
    public static String site(String azp, String siteId, String... roles) {
        var claims = Jwt.preferredUserName("service-account-" + azp).subject("sa-" + azp).issuer("https://server.example.com")
                .audience(java.util.Set.of("asset-service", azp)).claim("azp", azp)
                .claim("resource_access", Map.of("asset-service", Map.of("roles", List.of(roles)))).expiresIn(300);
        if (siteId != null) {
            claims.claim("siteId", siteId);
        }
        return claims.jws().keyId("1").sign("privateKey.jwk");
    }

    /** A token issued to asset-service itself that also carries a siteId claim. */
    public static String ownClientWithSite(String user, String siteId, String... roles) {
        return base(user).claim("siteId", siteId).claim("resource_access", Map.of("asset-service", Map.of("roles", List.of(roles))))
                .jws().keyId("1").sign("privateKey.jwk");
    }

    public static String otherAudience(String user, String... roles) {
        return Jwt.preferredUserName(user).subject(user).issuer("https://server.example.com").audience("developer-hub")
                .claim("resource_access", Map.of("asset-service", Map.of("roles", List.of(roles)))).expiresIn(300).jws().keyId("1")
                .sign("privateKey.jwk");
    }

    private static io.smallrye.jwt.build.JwtClaimsBuilder base(String user) {
        return Jwt.preferredUserName(user).subject(user).issuer("https://server.example.com").audience("asset-service").claim("azp", "asset-service")
                .expiresIn(300);
    }
}
