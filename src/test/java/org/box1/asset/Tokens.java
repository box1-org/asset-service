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
