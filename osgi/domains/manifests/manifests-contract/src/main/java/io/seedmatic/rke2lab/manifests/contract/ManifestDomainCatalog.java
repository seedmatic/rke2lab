package io.seedmatic.rke2lab.manifests.contract;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Canonical manifest-domain catalog shared across synthesis and controlplane policy.
 *
 * <p>The {@code public static final String} constants below are the single source of truth for
 * domain IDs. They are compile-time constants, so manifest units can compose their {@code
 * "<domain>/<leaf>"} IDs from them at static-initialization time (e.g. {@code CLUSTER_API +
 * "/operator"}) — something the instance accessors cannot do, since they require a built catalog.
 * The instance accessors and the builder's default-domain lists both reference these constants too,
 * so a domain string is spelled exactly once in the codebase.
 *
 * <p>A record — the two lists are its state; {@link #builder()} is the endorsed construction path
 * (validation lives in {@link Builder#build()}, the canonical constructor defensively copies). It
 * is a record, not a plain class, so it stays on the contract face (contract-purity: the seam
 * carries records / enums / interfaces, never a concrete class).
 */
public record ManifestDomainCatalog(List<String> all, List<String> stageALinkableDomains) {

  public static final String CLUSTER = "cluster";
  public static final String STORAGE = "storage";
  public static final String GITOPS = "gitops";
  public static final String RUNTIME = "runtime";
  public static final String NETWORKING = "networking";
  public static final String MESH = "mesh";
  public static final String HIGH_AVAILABILITY = "high-availability";
  public static final String CICD = "cicd";
  public static final String CLUSTER_API = "cluster-api";
  public static final String PLATFORM = "platform";
  public static final String INGRESS = "ingress";

  public ManifestDomainCatalog {
    all = List.copyOf(all);
    stageALinkableDomains = List.copyOf(stageALinkableDomains);
  }

  public static Builder builder() {
    return new Builder();
  }

  public String cluster() {
    return CLUSTER;
  }

  public String storage() {
    return STORAGE;
  }

  public String gitops() {
    return GITOPS;
  }

  public String runtime() {
    return RUNTIME;
  }

  public String networking() {
    return NETWORKING;
  }

  public String mesh() {
    return MESH;
  }

  public String highAvailability() {
    return HIGH_AVAILABILITY;
  }

  public String cicd() {
    return CICD;
  }

  public String clusterApi() {
    return CLUSTER_API;
  }

  public String platform() {
    return PLATFORM;
  }

  public String ingress() {
    return INGRESS;
  }

  public boolean isKnownDomainId(String domainId) {
    return all.contains(normalize(domainId));
  }

  public static final class Builder {
    private final LinkedHashSet<String> allDomains = new LinkedHashSet<>();
    private final LinkedHashSet<String> stageALinkableDomains = new LinkedHashSet<>();

    private Builder() {}

    public Builder addDefaultDomains() {
      return addAllDomains(
          List.of(
              CLUSTER,
              STORAGE,
              GITOPS,
              RUNTIME,
              NETWORKING,
              MESH,
              HIGH_AVAILABILITY,
              CICD,
              CLUSTER_API,
              PLATFORM,
              INGRESS));
    }

    public Builder addDefaultStageALinkableDomains() {
      // The publishable set = the operator's toggleable facet domains. Base infra (platform,
      // cluster, runtime) is always-on, never a publish knob.
      return addStageALinkableDomains(
          List.of(GITOPS, NETWORKING, CLUSTER_API, STORAGE, MESH, HIGH_AVAILABILITY, CICD));
    }

    public Builder addDomain(String domainId) {
      allDomains.add(normalizeNonBlank(domainId, "domainId"));
      return this;
    }

    public Builder addAllDomains(Iterable<String> domainIds) {
      Objects.requireNonNull(domainIds, "domainIds");
      for (String domainId : domainIds) {
        addDomain(domainId);
      }
      return this;
    }

    public Builder addStageALinkableDomain(String domainId) {
      stageALinkableDomains.add(normalizeNonBlank(domainId, "domainId"));
      return this;
    }

    public Builder addStageALinkableDomains(Iterable<String> domainIds) {
      Objects.requireNonNull(domainIds, "domainIds");
      for (String domainId : domainIds) {
        addStageALinkableDomain(domainId);
      }
      return this;
    }

    public Builder clearDomains() {
      allDomains.clear();
      return this;
    }

    public Builder clearStageALinkableDomains() {
      stageALinkableDomains.clear();
      return this;
    }

    public ManifestDomainCatalog build() {
      for (String domainId : stageALinkableDomains) {
        if (!allDomains.contains(domainId)) {
          throw new IllegalArgumentException(
              "Stage A linkable domain is not registered in catalog: " + domainId);
        }
      }
      if (allDomains.isEmpty()) {
        throw new IllegalArgumentException(
            "Manifest domain catalog must contain at least one domain");
      }
      return new ManifestDomainCatalog(List.copyOf(allDomains), List.copyOf(stageALinkableDomains));
    }
  }

  private static String normalizeNonBlank(String value, String fieldName) {
    final String normalized = normalize(value);
    if (normalized.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return normalized;
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim();
  }
}
