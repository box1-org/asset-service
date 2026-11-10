package org.box1.asset.api;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.eclipse.microprofile.openapi.OASFilter;
import org.eclipse.microprofile.openapi.models.Operation;
import org.eclipse.microprofile.openapi.models.security.SecurityRequirement;

/**
 * Drops security alternatives that another alternative of the same operation already implies. SmallRye adds one
 * alternative listing every {@code @RolesAllowed} role next to the declared one-role-each alternatives, which would make
 * /q/openapi differ from the contract (box1-ops-contracts openapi/asset-service.yaml) without changing its meaning.
 */
public class RedundantSecurityFilter implements OASFilter {
    @Override
    public Operation filterOperation(Operation operation) {
        List<SecurityRequirement> requirements = operation.getSecurity();
        if (requirements == null || requirements.size() < 2) {
            return operation;
        }
        List<SecurityRequirement> kept = new ArrayList<>();
        for (SecurityRequirement candidate : requirements) {
            boolean implied = requirements.stream().anyMatch(other -> other != candidate && implies(other, candidate));
            if (!implied) {
                kept.add(candidate);
            }
        }
        operation.setSecurity(kept);
        return operation;
    }

    /** True when {@code weaker} needs a strict subset of what {@code stronger} needs, scheme by scheme. */
    private static boolean implies(SecurityRequirement weaker, SecurityRequirement stronger) {
        Map<String, List<String>> w = weaker.getSchemes();
        Map<String, List<String>> s = stronger.getSchemes();
        if (w == null || s == null || w.isEmpty() || !s.keySet().containsAll(w.keySet())) {
            return false;
        }
        boolean strict = !w.keySet().equals(s.keySet());
        for (Map.Entry<String, List<String>> scheme : w.entrySet()) {
            HashSet<String> needs = new HashSet<>(scheme.getValue() == null ? List.of() : scheme.getValue());
            HashSet<String> has = new HashSet<>(s.get(scheme.getKey()) == null ? List.of() : s.get(scheme.getKey()));
            if (!has.containsAll(needs)) {
                return false;
            }
            strict |= has.size() > needs.size();
        }
        return strict;
    }
}
