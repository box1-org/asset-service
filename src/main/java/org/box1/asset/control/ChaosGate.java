package org.box1.asset.control;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import org.box1.asset.config.AssetConfig;

/**
 * Chaos runs only when a flag enables it AND the pod's own namespace is allowlisted (D-015). The namespace comes from
 * the downward API; when it is unknown, the gate is closed.
 */
@ApplicationScoped
public class ChaosGate {
    private final boolean enabled;
    private final List<String> allowlist;
    private final String namespace;
    private final String environment;

    public ChaosGate(AssetConfig config) {
        this.enabled = config.chaos().enabled();
        this.allowlist = config.chaos().namespaceAllowlist().orElse(List.of()).stream().map(String::trim)
                .filter(s -> !s.isEmpty()).toList();
        this.namespace = config.chaos().podNamespace().map(String::trim).filter(s -> !s.isEmpty()).orElse(null);
        this.environment = config.chaos().environment();
    }

    public Decision decide() {
        if (!enabled) {
            return new Decision(false, "CHAOS_DISABLED", "Chaos is disabled by configuration (asset.chaos.enabled=false)");
        }
        if (namespace == null) {
            return new Decision(false, "NAMESPACE_UNKNOWN", "The pod namespace is unknown, so it cannot be checked against the allowlist");
        }
        if (!allowlist.contains(namespace)) {
            return new Decision(false, "NAMESPACE_NOT_ALLOWLISTED", "Namespace " + namespace + " is not on the chaos allowlist");
        }
        return new Decision(true, "OPEN", "Chaos is enabled and namespace " + namespace + " is allowlisted");
    }

    public String namespace() {
        return namespace == null ? "unknown" : namespace;
    }

    public String environment() {
        return environment;
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean allowlisted() {
        return namespace != null && allowlist.contains(namespace);
    }

    public record Decision(boolean open, String code, String message) {
    }
}
