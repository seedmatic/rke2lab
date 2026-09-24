package io.seedmatic.rke2lab.controlplane.config;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.osgi.service.log.LogLevel;

/**
 * Root configuration DTO and single source of truth. Infra fragments are contributed by each {@link
 * InfraDomain} (via {@link InfraConfigRegistry}); cross-cutting identity is loaded directly.
 * Mandatory fields are plain types validated at load; optional fields are {@code Optional} with NO
 * defaults — defaults are applied in the derivation layer ({@code BootstrapConfig.from} / {@code
 * BootstrapOptions.from}).
 */
public record Rke2labConfig(
    InfraConfigRegistry infra,
    ClusterConfig cluster,
    NodeConfig node,
    ApiConfig api,
    KubeconfigConfig kubeconfig,
    ProvisioningPolicyConfig provisioning,
    ReadinessConfig readiness,
    EntryGateConfig entryGate,
    ManifestsConfig manifests,
    LoggingConfig logging) {

  /**
   * Parse the {@code logging:level} knob into the OSGi {@link LogLevel} — the SAME enum the
   * framework boot and the {@code @FrameworkLog} test annotation speak, so the operator's one knob
   * has one vocabulary. Case-insensitive over the OSGi names (AUDIT/ERROR/WARN/INFO/DEBUG/TRACE);
   * an unknown value fails loudly with the accepted set rather than defaulting silently.
   */
  private static LogLevel parseLogLevel(String raw) {
    try {
      return LogLevel.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException(
          "logging:level '"
              + raw
              + "' is not an org.osgi.service.log.LogLevel (AUDIT, ERROR, WARN, INFO, DEBUG, TRACE)",
          ex);
    }
  }

  /** Offline path: empty config, no mandatory validation. Derivation applies all defaults. */
  public static Rke2labConfig defaults() {
    return build(ConfigLoader.of(section -> Optional.empty()), false);
  }

  /**
   * Build from any loader (live wraps Pulumi config; the entry gate and offline tests pass an
   * in-memory loader). Validates mandatory keys — throws {@link MissingRequiredConfiguration}
   * naming every absent one.
   */
  public static Rke2labConfig from(ConfigLoader loader) {
    return build(loader, true);
  }

  /**
   * The per-checkpoint readiness overrides — one {@link ReadinessConfig.CheckpointDeadlines} for
   * each child object under {@code readiness} (the scalar knobs {@code connectTimeout}/{@code
   * timeout} are the global level, not objects, so {@code objectKeys} leaves them out). Keyed by
   * the checkpoint slug the operator names, matched later to a scenario's {@code
   * readinessCheckpoint()}.
   */
  private static Map<String, ReadinessConfig.CheckpointDeadlines> readinessCheckpoints(
      ConfigLoader loader) {
    final LinkedHashMap<String, ReadinessConfig.CheckpointDeadlines> byCheckpoint =
        new LinkedHashMap<>();
    for (final String slug : loader.objectKeys("readiness")) {
      byCheckpoint.put(
          slug,
          new ReadinessConfig.CheckpointDeadlines(
              loader.optionalDuration("readiness." + slug, "connectTimeout"),
              loader.optionalDuration("readiness." + slug, "timeout")));
    }
    return Map.copyOf(byCheckpoint);
  }

  private static Rke2labConfig build(ConfigLoader loader, boolean validate) {
    final InfraConfigRegistry infra = InfraConfigRegistry.from(loader);

    final Rke2labConfig dto =
        new Rke2labConfig(
            infra,
            new ClusterConfig(
                loader.optional("cluster", "host"),
                loader.optional("cluster", "role"),
                loader.optional("cluster", "remoteIncus")),
            new NodeConfig(loader.optional("node", "name")),
            new ApiConfig(loader.optionalUri("api", "endpoint")),
            new KubeconfigConfig(loader.optionalPath("kubeconfig", "ref")),
            new ProvisioningPolicyConfig(
                loader.optionalBoolean("policy.network.lan.binding", "enabled"),
                loader.optionalBoolean("policy.gitDirtyCheck", "enabled")),
            new ReadinessConfig(
                loader.optionalBoolean("readiness", "enabled"),
                loader.optionalDuration("readiness", "connectTimeout"),
                loader.optionalDuration("readiness", "timeout"),
                readinessCheckpoints(loader)),
            new EntryGateConfig(
                loader.optionalBoolean("entryGate.cleanWorktree", "required"),
                loader.stringList("entryGate.cleanWorktree", "tolerated"),
                loader.optionalBoolean("entryGate.flakeLock", "required")),
            loader.bind(ManifestsConfig.class, "manifests"),
            new LoggingConfig(
                loader.optional("logging", "level").map(Rke2labConfig::parseLogLevel)));

    if (validate) {
      loader.diagnoseIfIncomplete();
    }
    return dto;
  }

  public IncusConfig incus() {
    return infra.fragment(InfraDomainCatalog.INCUS, IncusConfig.class);
  }

  public ImageConfig image() {
    return infra.fragment(InfraDomainCatalog.IMAGE, ImageConfig.class);
  }

  public NetworkConfig network() {
    return infra.fragment(InfraDomainCatalog.NETWORK, NetworkConfig.class);
  }

  public SystemdAdapterConfig systemd() {
    return infra.fragment(InfraDomainCatalog.SYSTEMD, SystemdAdapterConfig.class);
  }

  public HostAssetConfig hostAsset() {
    return infra.fragment(InfraDomainCatalog.HOST, HostAssetConfig.class);
  }

  // --- Infra fragments (sealed marker) ---

  public record IncusConfig(Optional<String> project, Optional<URI> remoteAddress, Path configDir)
      implements InfraConfigFragment {}

  public record ImageConfig(Optional<String> builderHost) implements InfraConfigFragment {}

  public record NetworkConfig(
      Optional<String> fabricBridgeParent, Optional<Boolean> automount, Optional<String> tailnet)
      implements InfraConfigFragment {}

  public record SystemdAdapterConfig(Optional<String> dbusHost, Optional<Integer> dbusPort)
      implements InfraConfigFragment {}

  public record HostAssetConfig(Optional<Integer> rotationRetentionCount)
      implements InfraConfigFragment {}

  // --- Cross-cutting identity (no marker) ---

  /**
   * The cluster identity atoms — the SINGLE SOURCE OF TRUTH the cluster name derives from ({@code
   * clusterName = <host>-<role>}, never stored). {@code host} names the incus substrate the
   * cluster's nodes grow on (bioskop/nikopol); {@code role} is mgmt/wrkld. {@code remoteIncus} is
   * the explicit incus remote LABEL (the {@code <host>-nixos} daemon host) — the one place that
   * name is written, not decomposed from the cluster name; absent, the derivation defaults it once.
   */
  public record ClusterConfig(
      Optional<String> host, Optional<String> role, Optional<String> remoteIncus) {}

  public record NodeConfig(Optional<String> name) {}

  public record ApiConfig(Optional<URI> endpoint) {}

  public record KubeconfigConfig(Optional<Path> ref) {}

  public record ProvisioningPolicyConfig(
      Optional<Boolean> lanBinding, Optional<Boolean> gitDirtyCheck) {}

  /**
   * The readiness deadlines the operator tunes under {@code rke2lab:readiness:}. Two levels: {@code
   * connectTimeout}/{@code timeout} are the GLOBAL defaults (every checkpoint), and {@link
   * #checkpoints} carries per-checkpoint overrides keyed by the checkpoint slug ({@code
   * systemd-adapter}, {@code cluster-readiness}) — a per-checkpoint half wins over the global, and
   * whatever both leave empty falls to the scenario's {@code @ReadinessDeadlines} annotation.
   * {@code enabled} is the pre-existing on/off knob. Absent everywhere ⇒ the annotation defaults
   * stand.
   */
  public record ReadinessConfig(
      Optional<Boolean> enabled,
      Optional<Duration> connectTimeout,
      Optional<Duration> timeout,
      Map<String, CheckpointDeadlines> checkpoints) {

    public ReadinessConfig {
      checkpoints = checkpoints == null ? Map.of() : Map.copyOf(checkpoints);
    }

    /** One checkpoint's override of the two deadlines — each half optional. */
    public record CheckpointDeadlines(
        Optional<Duration> connectTimeout, Optional<Duration> timeout) {}
  }

  /**
   * The worktree entry gate. {@code cleanWorktreeRequired} arms the clean-worktree sub-gate and
   * {@code toleratedPaths} is its injected leniency — the worktree-relative paths (or path
   * prefixes) allowed to be uncommitted while it still passes (the ambient files a multi-session
   * working tree carries: {@code .secrets}, {@code Pulumi.dev.yaml}, …). {@code flakeLockRequired}
   * arms the flake-lock-coherence sub-gate (default OFF). The gate itself hardcodes NONE of this;
   * the policy is the operator's, declared here.
   */
  public record EntryGateConfig(
      Optional<Boolean> cleanWorktreeRequired,
      List<String> toleratedPaths,
      Optional<Boolean> flakeLockRequired) {}

  /**
   * The manifests coordinate — the operator's {@code rke2lab:manifests:} concern ({@code {publish,
   * debug}}) the host contributes BLIND. It has NO typed input and NO {@link SecretJoin}: the WHOLE
   * subtree lands in {@link #rest}, so the host names no manifests vocabulary (the publish flags,
   * the debug nesting) — ALL the domain knowledge lives in the scion (see {@code
   * io.seedmatic.rke2lab.manifests.contract.ManifestsRunbookInput}). {@link #facetJson()}
   * re-serialises the remainder verbatim.
   */
  public record ManifestsConfig(@JsonAnySetter Map<String, Object> rest) implements Facet {

    public ManifestsConfig {
      rest = rest == null ? Map.of() : rest;
    }

    @JsonAnyGetter
    public Map<String, Object> rest() {
      return rest;
    }

    @Override
    public String facetJson() {
      return rest.isEmpty() ? "" : ConfigLoader.writeJson(this);
    }
  }

  /**
   * The framework log-level knob ({@code logging:level}). Empty ⇒ no override: the boot keeps the
   * Felix default and the generated pax logback keeps its {@code ${seed.log.level}} default.
   */
  public record LoggingConfig(Optional<LogLevel> level) {}
}
