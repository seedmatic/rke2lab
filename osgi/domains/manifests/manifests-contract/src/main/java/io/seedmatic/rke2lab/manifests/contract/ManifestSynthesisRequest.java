package io.seedmatic.rke2lab.manifests.contract;

import io.seedmatic.rke2lab.manifests.contract.profiles.BootstrapIdentity;
import io.seedmatic.rke2lab.manifests.contract.profiles.ClusterIssuerCaMaterial;
import io.seedmatic.rke2lab.manifests.contract.profiles.FloxDebugPolicy;
import io.seedmatic.rke2lab.manifests.contract.profiles.GithubAppMaterial;
import io.seedmatic.rke2lab.manifests.contract.profiles.ImageState;
import io.seedmatic.rke2lab.manifests.contract.profiles.IncusIdentityMaterial;
import io.seedmatic.rke2lab.manifests.contract.profiles.ManagementClusterCaMaterial;
import io.seedmatic.rke2lab.manifests.contract.profiles.OperatorPkiMaterial;
import io.seedmatic.rke2lab.manifests.contract.profiles.ReplicatorSourceSecretsMaterial;
import io.seedmatic.rke2lab.manifests.contract.profiles.WorkloadClusterCasMaterial;
import io.seedmatic.rke2lab.manifests.ingress.ComponentVersions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Request contract for canonical manifest synthesis. */
public record ManifestSynthesisRequest(
    Path synthOutdir,
    Path synthManifestFile,
    Optional<ManifestDomainPolicy> manifestDomainPolicy,
    FloxDebugPolicy floxDebugPolicy,
    BootstrapIdentity bootstrapIdentity,
    ComponentVersions componentVersions,
    Optional<ImageState> imageState,
    Optional<IncusIdentityMaterial> incusIdentity,
    Optional<OperatorPkiMaterial> operatorPki,
    Optional<GithubAppMaterial> githubApp,
    Optional<ReplicatorSourceSecretsMaterial> replicatorSources,
    Optional<ClusterIssuerCaMaterial> clusterIssuerCa,
    Optional<WorkloadClusterCasMaterial> workloadCas,
    Optional<ManagementClusterCaMaterial> managementCas,
    List<WorkloadTarget> workloadTargets)
    implements ManifestDomainPolicyAware {

  private static final String ENABLED_DOMAINS_PROPERTY = "rke2lab.manifests.policy.enabledDomains";

  private static final ManifestDomainCatalog MANIFEST_DOMAIN_CATALOG =
      ManifestDomainCatalog.builder().addDefaultDomains().addDefaultStageALinkableDomains().build();

  public ManifestSynthesisRequest {
    synthOutdir = synthOutdir.toAbsolutePath().normalize();
    synthManifestFile = synthManifestFile.toAbsolutePath().normalize();
    manifestDomainPolicy =
        manifestDomainPolicy == null
            ? Optional.empty()
            : manifestDomainPolicy.map(policy -> policy);
    floxDebugPolicy = floxDebugPolicy == null ? FloxDebugPolicy.disabled() : floxDebugPolicy;
    bootstrapIdentity = bootstrapIdentity == null ? BootstrapIdentity.unknown() : bootstrapIdentity;
    // No blank-version fallback: an absent ComponentVersions is incomplete state, not a valid empty
    // default — a blank version renders an unresolvable upstream path (e.g. release-.yaml). The
    // builder supplies ComponentVersions.defaults(); the engine (seed-master) overlays Pulumi
    // config
    // on top. Required by construction, so a version-less request cannot exist.
    componentVersions =
        Objects.requireNonNull(
            componentVersions, "componentVersions is required (no blank-version default)");
    imageState = imageState == null ? Optional.empty() : imageState;
    incusIdentity = incusIdentity == null ? Optional.empty() : incusIdentity;
    operatorPki = operatorPki == null ? Optional.empty() : operatorPki;
    githubApp = githubApp == null ? Optional.empty() : githubApp;
    replicatorSources = replicatorSources == null ? Optional.empty() : replicatorSources;
    clusterIssuerCa = clusterIssuerCa == null ? Optional.empty() : clusterIssuerCa;
    workloadCas = workloadCas == null ? Optional.empty() : workloadCas;
    managementCas = managementCas == null ? Optional.empty() : managementCas;
    // The workload clusters this (management) render must emit CAPI CRs for — empty on a mgmt-only
    // or standalone run. Coalesced to an empty immutable list so a slice-less request never NPEs.
    workloadTargets = workloadTargets != null ? List.copyOf(workloadTargets) : List.of();
  }

  public static Builder builder(Path synthOutdir, Path synthManifestFile) {
    return new Builder(synthOutdir, synthManifestFile);
  }

  /** A builder pre-loaded with this request's values, for immutable transformation. */
  public Builder toBuilder() {
    return new Builder(synthOutdir, synthManifestFile)
        .manifestDomainPolicy(manifestDomainPolicy)
        .floxDebugPolicy(floxDebugPolicy)
        .bootstrapIdentity(bootstrapIdentity)
        .componentVersions(componentVersions)
        .imageState(imageState)
        .incusIdentity(incusIdentity)
        .operatorPki(operatorPki)
        .githubApp(githubApp)
        .replicatorSources(replicatorSources)
        .clusterIssuerCa(clusterIssuerCa)
        .workloadCas(workloadCas)
        .managementCas(managementCas)
        .workloadTargets(workloadTargets);
  }

  // Immutable transformations: each returns a new request with one slice replaced. They delegate to
  // toBuilder() so the field list lives in exactly one place (the Builder) — adding a slice never
  // touches these.
  public ManifestSynthesisRequest withManifestDomainPolicy(ManifestDomainPolicy policy) {
    return toBuilder().manifestDomainPolicy(Optional.of(policy)).build();
  }

  public ManifestSynthesisRequest withFloxDebugPolicy(FloxDebugPolicy policy) {
    return toBuilder().floxDebugPolicy(policy).build();
  }

  public ManifestSynthesisRequest withBootstrapIdentity(BootstrapIdentity identity) {
    return toBuilder().bootstrapIdentity(identity).build();
  }

  public ManifestSynthesisRequest withComponentVersions(ComponentVersions versions) {
    return toBuilder().componentVersions(versions).build();
  }

  public ManifestSynthesisRequest withImageState(ImageState state) {
    return toBuilder().imageState(Optional.of(state)).build();
  }

  public ManifestSynthesisRequest withIncusIdentity(IncusIdentityMaterial material) {
    return toBuilder().incusIdentity(Optional.of(material)).build();
  }

  public ManifestSynthesisRequest withOperatorPki(OperatorPkiMaterial material) {
    return toBuilder().operatorPki(Optional.of(material)).build();
  }

  public ManifestSynthesisRequest withGithubApp(GithubAppMaterial material) {
    return toBuilder().githubApp(Optional.of(material)).build();
  }

  public ManifestSynthesisRequest withReplicatorSources(ReplicatorSourceSecretsMaterial material) {
    return toBuilder().replicatorSources(Optional.of(material)).build();
  }

  public ManifestSynthesisRequest withClusterIssuerCa(ClusterIssuerCaMaterial material) {
    return toBuilder().clusterIssuerCa(Optional.of(material)).build();
  }

  public ManifestSynthesisRequest withWorkloadCas(WorkloadClusterCasMaterial material) {
    return toBuilder().workloadCas(Optional.of(material)).build();
  }

  public ManifestSynthesisRequest withManagementCas(ManagementClusterCaMaterial material) {
    return toBuilder().managementCas(Optional.of(material)).build();
  }

  public static ManifestSynthesisRequest fromSystemProperties() {
    final String outdirProperty = System.getProperty("rke2lab.manifests.outdir");
    final String fileProperty = System.getProperty("rke2lab.manifests.file");
    final Optional<ManifestDomainPolicy> manifestDomainPolicy = policyFromSystemProperties();
    final FloxDebugPolicy floxDebugPolicy = floxDebugPolicyFromSystemProperties();

    if (outdirProperty == null && fileProperty == null) {
      return ephemeral(manifestDomainPolicy, floxDebugPolicy);
    }

    final Path outdir;
    final Path manifestFile;
    if (outdirProperty != null && fileProperty != null) {
      outdir = Paths.get(outdirProperty);
      manifestFile = Paths.get(fileProperty);
    } else if (outdirProperty != null) {
      outdir = Paths.get(outdirProperty);
      manifestFile = outdir.resolve("manifests.yaml");
    } else {
      manifestFile = Paths.get(fileProperty);
      outdir = manifestFile.getParent() == null ? Paths.get(".") : manifestFile.getParent();
    }
    return builder(outdir, manifestFile)
        .manifestDomainPolicy(manifestDomainPolicy)
        .floxDebugPolicy(floxDebugPolicy)
        .build();
  }

  public static ManifestSynthesisRequest ephemeral() {
    return ephemeral(Optional.empty(), FloxDebugPolicy.disabled());
  }

  public static ManifestSynthesisRequest ephemeral(ManifestDomainPolicy manifestDomainPolicy) {
    return ephemeral(Optional.of(manifestDomainPolicy), FloxDebugPolicy.disabled());
  }

  private static ManifestSynthesisRequest ephemeral(
      Optional<ManifestDomainPolicy> manifestDomainPolicy, FloxDebugPolicy floxDebugPolicy) {
    try {
      final Path outdir =
          Files.createTempDirectory("rke2lab-manifests-").toAbsolutePath().normalize();
      final Path manifestFile = outdir.resolve("manifests.yaml");
      return builder(outdir, manifestFile)
          .manifestDomainPolicy(manifestDomainPolicy)
          .floxDebugPolicy(floxDebugPolicy)
          .build();
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed to create temporary synthesis directory", ex);
    }
  }

  /** The single construction path: the two output paths are required, every slice optional. */
  public static final class Builder {
    private final Path synthOutdir;
    private final Path synthManifestFile;
    private Optional<ManifestDomainPolicy> manifestDomainPolicy = Optional.empty();
    private FloxDebugPolicy floxDebugPolicy = FloxDebugPolicy.disabled();
    private BootstrapIdentity bootstrapIdentity = BootstrapIdentity.unknown();
    private ComponentVersions componentVersions = ComponentVersions.defaults();
    private Optional<ImageState> imageState = Optional.empty();
    private Optional<IncusIdentityMaterial> incusIdentity = Optional.empty();
    private Optional<OperatorPkiMaterial> operatorPki = Optional.empty();
    private Optional<GithubAppMaterial> githubApp = Optional.empty();
    private Optional<ReplicatorSourceSecretsMaterial> replicatorSources = Optional.empty();
    private Optional<ClusterIssuerCaMaterial> clusterIssuerCa = Optional.empty();
    private Optional<WorkloadClusterCasMaterial> workloadCas = Optional.empty();
    private Optional<ManagementClusterCaMaterial> managementCas = Optional.empty();
    private List<WorkloadTarget> workloadTargets = List.of();

    private Builder(Path synthOutdir, Path synthManifestFile) {
      this.synthOutdir = synthOutdir;
      this.synthManifestFile = synthManifestFile;
    }

    public Builder manifestDomainPolicy(final Optional<ManifestDomainPolicy> v) {
      this.manifestDomainPolicy = v;
      return this;
    }

    public Builder floxDebugPolicy(final FloxDebugPolicy v) {
      this.floxDebugPolicy = v;
      return this;
    }

    public Builder bootstrapIdentity(final BootstrapIdentity v) {
      this.bootstrapIdentity = v;
      return this;
    }

    public Builder componentVersions(final ComponentVersions v) {
      this.componentVersions = v;
      return this;
    }

    public Builder imageState(final Optional<ImageState> v) {
      this.imageState = v;
      return this;
    }

    public Builder incusIdentity(final Optional<IncusIdentityMaterial> v) {
      this.incusIdentity = v;
      return this;
    }

    public Builder operatorPki(final Optional<OperatorPkiMaterial> v) {
      this.operatorPki = v;
      return this;
    }

    public Builder githubApp(final Optional<GithubAppMaterial> v) {
      this.githubApp = v;
      return this;
    }

    public Builder replicatorSources(final Optional<ReplicatorSourceSecretsMaterial> v) {
      this.replicatorSources = v;
      return this;
    }

    public Builder clusterIssuerCa(final Optional<ClusterIssuerCaMaterial> v) {
      this.clusterIssuerCa = v;
      return this;
    }

    public Builder workloadCas(final Optional<WorkloadClusterCasMaterial> v) {
      this.workloadCas = v;
      return this;
    }

    public Builder managementCas(final Optional<ManagementClusterCaMaterial> v) {
      this.managementCas = v;
      return this;
    }

    public Builder workloadTargets(final List<WorkloadTarget> v) {
      this.workloadTargets = v;
      return this;
    }

    public ManifestSynthesisRequest build() {
      return new ManifestSynthesisRequest(
          synthOutdir,
          synthManifestFile,
          manifestDomainPolicy,
          floxDebugPolicy,
          bootstrapIdentity,
          componentVersions,
          imageState,
          incusIdentity,
          operatorPki,
          githubApp,
          replicatorSources,
          clusterIssuerCa,
          workloadCas,
          managementCas,
          workloadTargets);
    }
  }

  private static FloxDebugPolicy floxDebugPolicyFromSystemProperties() {
    return new FloxDebugPolicy(
        boolSystemProperty("rke2lab.manifests.policy.debug.mesh.enabled"),
        boolSystemProperty("rke2lab.manifests.policy.debug.networking.enabled"),
        boolSystemProperty("rke2lab.manifests.policy.debug.nriPlugins.flox.enabled"));
  }

  private static boolean boolSystemProperty(final String key) {
    final String value = System.getProperty(key);
    if (value == null || value.isBlank()) {
      return false;
    }
    return switch (value.trim().toLowerCase()) {
      case "1", "true", "yes", "on" -> true;
      default -> false;
    };
  }

  private static Optional<ManifestDomainPolicy> policyFromSystemProperties() {
    final String enabledDomainsProperty = System.getProperty(ENABLED_DOMAINS_PROPERTY);
    if (enabledDomainsProperty == null || enabledDomainsProperty.isBlank()) {
      return Optional.empty();
    }

    return Optional.of(
        ManifestDomainPolicy.builder()
            .domainCatalog(MANIFEST_DOMAIN_CATALOG)
            .enableOnly(
                Arrays.stream(enabledDomainsProperty.split(","))
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .toList())
            .build());
  }
}
