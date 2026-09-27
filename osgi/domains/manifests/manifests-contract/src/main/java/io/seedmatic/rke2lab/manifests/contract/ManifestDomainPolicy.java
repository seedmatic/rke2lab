package io.seedmatic.rke2lab.manifests.contract;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Canonical manifest-domain policy shared between cdk8s synthesis concepts and controlplane
 * activation logic.
 */
public record ManifestDomainPolicy(Map<String, Boolean> enabledByDomainId) {

  public ManifestDomainPolicy {
    enabledByDomainId = Map.copyOf(new LinkedHashMap<>(enabledByDomainId));
  }

  public static Builder builder() {
    return new Builder();
  }

  public boolean isEnabled(String domainId) {
    return enabledByDomainId.getOrDefault(domainId, false);
  }

  public List<String> enabledDomainIds() {
    return enabledByDomainId.entrySet().stream()
        .filter(Map.Entry::getValue)
        .map(Map.Entry::getKey)
        .sorted()
        .toList();
  }

  public List<String> domainIds() {
    return enabledByDomainId.keySet().stream().sorted().toList();
  }

  public Map<String, Boolean> asMap() {
    return enabledByDomainId;
  }

  public static final class Builder {
    private ManifestDomainCatalog domainCatalog =
        ManifestDomainCatalog.builder()
            .addDefaultDomains()
            .addDefaultStageALinkableDomains()
            .build();

    private final LinkedHashMap<String, Boolean> enabledByDomainId = new LinkedHashMap<>();

    private Builder() {
      resetAllDisabled();
    }

    public Builder domainCatalog(ManifestDomainCatalog domainCatalog) {
      this.domainCatalog = Objects.requireNonNull(domainCatalog, "domainCatalog");
      resetAllDisabled();
      return this;
    }

    public Builder stageADefaults() {
      resetAllDisabled();
      return this;
    }

    public Builder highAvailability(boolean enabled) {
      put(domainCatalog.highAvailability(), enabled);
      return this;
    }

    public Builder networking(boolean enabled) {
      put(domainCatalog.networking(), enabled);
      return this;
    }

    public Builder storage(boolean enabled) {
      put(domainCatalog.storage(), enabled);
      return this;
    }

    public Builder clusterApi(boolean enabled) {
      put(domainCatalog.clusterApi(), enabled);
      return this;
    }

    public Builder platform(boolean enabled) {
      put(domainCatalog.platform(), enabled);
      return this;
    }

    public Builder cicd(boolean enabled) {
      put(domainCatalog.cicd(), enabled);
      return this;
    }

    public Builder gitops(boolean enabled) {
      put(domainCatalog.gitops(), enabled);
      return this;
    }

    public Builder runtime(boolean enabled) {
      put(domainCatalog.runtime(), enabled);
      return this;
    }

    public Builder cluster(boolean enabled) {
      put(domainCatalog.cluster(), enabled);
      return this;
    }

    public Builder enableOnly(Iterable<String> enabledDomainIds) {
      resetAllDisabled();
      for (String domainId : enabledDomainIds) {
        final String normalizedDomainId = normalize(domainId);
        if (normalizedDomainId.isBlank()) {
          continue;
        }
        ensureKnownDomainId(normalizedDomainId);
        put(normalizedDomainId, true);
      }
      return this;
    }

    public Builder setEnabled(String domainId, boolean enabled) {
      final String normalizedDomainId = normalize(domainId);
      ensureKnownDomainId(normalizedDomainId);
      put(normalizedDomainId, enabled);
      return this;
    }

    public ManifestDomainPolicy build() {
      return new ManifestDomainPolicy(enabledByDomainId);
    }

    private void resetAllDisabled() {
      enabledByDomainId.clear();
      for (String domainId : domainCatalog.all()) {
        enabledByDomainId.put(domainId, false);
      }
    }

    private void ensureKnownDomainId(String domainId) {
      if (!enabledByDomainId.containsKey(domainId)) {
        throw new IllegalArgumentException("Unknown manifest domain id in policy: " + domainId);
      }
    }

    private void put(String domainId, boolean enabled) {
      enabledByDomainId.put(domainId, enabled);
    }

    private static String normalize(String value) {
      return value == null ? "" : value.trim();
    }
  }
}
