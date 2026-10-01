package io.seedmatic.rke2lab.controlplane.config;

import io.seedmatic.rke2lab.incus.ingress.BootstrapPaths;
import io.seedmatic.rke2lab.seed.broker.port.ReadinessDeadlineOverride;
import io.seedmatic.rke2lab.seed.broker.port.ReadinessOverrides;
import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.osgi.service.log.LogLevel;

/**
 * Runtime configuration for provider-native Stage A bootstrap, derived from {@link Rke2labConfig}.
 */
public record BootstrapConfig(
    String host,
    String role,
    String clusterName,
    String nodeName,
    String incusProject,
    String incusDefaultRemote,
    URI incusRemoteAddress,
    URI incusClusterAddress,
    Path incusConfigFolder,
    String imageAlias,
    String imageBuilderHost,
    String fabricBridgeParent,
    String tailnet,
    URI apiEndpoint,
    Path kubeconfigRef,
    boolean automount,
    String systemdAdapterDbusHost,
    int systemdAdapterDbusPort,
    int hostAssetRotationRetentionCount,
    ReadinessOverrides readinessOverrides,
    Optional<LogLevel> logLevel) {

  // Defaults applied here, at the single derivation site — formerly the Builder field initializers
  // and the env/JGit/user.home detection in the deleted Defaults class.
  private static final String DEFAULT_HOST = "bioskop";
  private static final String DEFAULT_ROLE = "mgmt";
  private static final String DEFAULT_NODE_NAME = "master";
  private static final String DEFAULT_INCUS_PROJECT = "rke2lab";
  // The one seed image's incus alias — the host adopts the built image by it. Single source here;
  // the build script (build-node-base-image.sh) hardcodes the SAME literal on the import side.
  private static final String IMAGE_ALIAS = "node-base";
  private static final String DEFAULT_FABRIC_BRIDGE_PARENT = "fabric-br";
  // The tailscale tailnet DNS suffix. Resolvable host/automount addresses use the MagicDNS FQDN
  // <host>.<tailnet> so they route over the tailscale overlay (stable across the physical LAN),
  // rather than the LAN mDNS <host>.local. Package-visible so the ghapp CLI pre-fills the App
  // registration form's webhook URL with the same funnel FQDN the grow reconciles to.
  static final String DEFAULT_TAILNET = "mammoth-skate.ts.net";

  /**
   * The cluster whose funnel the GitHub App's single webhook URL points at, for the standalone
   * pre-fill (`ghapp create`) that runs BEFORE any App or config exists. Per-cluster funnels mean
   * each cluster has its own PaC door, but an App has one webhook — so this names the management
   * one, the same deployment-default nature as DEFAULT_TAILNET beside it.
   */
  static final String DEFAULT_CLUSTER = "bioskop-mgmt";

  private static final URI DEFAULT_API_ENDPOINT = URI.create("https://10.66.106.10:6443");
  private static final boolean DEFAULT_AUTOMOUNT = true;
  private static final int DEFAULT_SYSTEMD_ADAPTER_DBUS_PORT = 12434;
  private static final int DEFAULT_HOST_ASSET_ROTATION_RETENTION_COUNT = 3;

  /**
   * Derive the Stage A bootstrap config from the root DTO. The worktree root is NOT here: it is the
   * worktree soil's harvest (its {@code Worktree} component self-locates it), fetched from the
   * cellar by whoever needs it — storing it in config would only collide across worktrees. The
   * mandatory config value ({@code incus.configDir}) is already validated at load, so it arrives
   * non-null; optional values get their default here.
   */
  public static BootstrapConfig from(Rke2labConfig config) {
    // The cluster identity atoms are the single source of truth; the cluster name is DERIVED
    // <host>-<role> (bioskop-mgmt), never stored. host names the incus substrate the nodes grow on;
    // role is mgmt/wrkld — so two clusters on one host (bioskop-mgmt / bioskop-wrkld) stay distinct
    // in every derived name (branch, node fqdn, k8s cluster).
    final String host = config.cluster().host().orElse(DEFAULT_HOST);
    final String role = config.cluster().role().orElse(DEFAULT_ROLE);
    final String clusterName = host + "-" + role;
    final String nodeName = config.node().name().orElse(DEFAULT_NODE_NAME);
    // The incus/nixos daemon host — the SINGLE place the "<host>-nixos" convention is spelled.
    // Explicit rke2lab:cluster:remoteIncus wins (a mgmt cluster grows a workload on ANOTHER host's
    // remote, so it is config, not decomposed from the cluster name); absent, defaulted here once.
    // It is the incus remote LABEL only — NOT an address. The three resolvable forms it used to
    // stand for now all come from `nixosFabricHost` below.
    final String remoteIncus = config.cluster().remoteIncus().orElseGet(() -> host + "-nixos");
    // ★ The ONE resolvable form, for every audience — operator, pod and ssh builder alike. It was
    // the in-cluster form only, beside an mDNS `<host>-nixos.local` for the operator and a bare
    // tailnet name for the Go provider; keeping three spellings of one daemon is what made this the
    // THIRD place in a day where a name nobody declares answered first (the ssh `nixos.` alias and
    // the Incus cluster member URL were the other two).
    //
    // Measured 2026-10-01 on the renewed hosts, with getaddrinfo — what every one of these callers
    // actually uses: `nixos.<host>` resolves to the host's fabric address through that bare-metal's
    // own dnsmasq, `<host>-nixos` is captured by the home LAN via the `lan` search domain, and
    // `.local` stalls ~5s on macOS. One of the three is ours.
    //
    // A pod reaches it too (it resolves through CoreDNS, which sees neither mDNS nor MagicDNS), and
    // the tailnet grant that makes a cluster node's fabric source legible is what finally closed
    // that path — see ndh `manage-tailnet` baremetalNodeCidrs.
    //
    // Derived from the `host` ATOM, not from `remoteIncus`: `host` names the incus substrate the
    // nodes grow on, so it is the bare-metal whose dnsmasq serves the `.<host>` zone, while
    // `remoteIncus` is only that daemon's remote LABEL. Spelled literally here for the same reason
    // `.local` is — this is the host world, past the OSGi seam; the netplan mirror is
    // ClusterNetworkBlueprint.NamePlan.nixosFabricFqdn, which says why this form replaced a `.lan`
    // one.
    final String nixosFabricHost = "nixos." + host;

    // Flat kubeconfig at .local.d/kubeconfig.yaml — one single-node management cluster.
    final Path kubeconfigRef =
        config
            .kubeconfig()
            .ref()
            .orElseGet(() -> Path.of(BootstrapPaths.STATE_DIR, "kubeconfig.yaml").normalize());

    return new BootstrapConfig(
        host,
        role,
        clusterName,
        nodeName,
        config.incus().project().orElse(DEFAULT_INCUS_PROJECT),
        remoteIncus,
        config
            .incus()
            .remoteAddress()
            .orElseGet(() -> URI.create("https://" + nixosFabricHost + ":8443")),
        URI.create("https://" + nixosFabricHost + ":8443"),
        config.incus().configDir(),
        IMAGE_ALIAS,
        config.image().builderHost().orElseGet(() -> nixosFabricHost),
        config.network().fabricBridgeParent().orElse(DEFAULT_FABRIC_BRIDGE_PARENT),
        config.network().tailnet().orElse(DEFAULT_TAILNET),
        config.api().endpoint().orElse(DEFAULT_API_ENDPOINT),
        kubeconfigRef,
        config.network().automount().orElse(DEFAULT_AUTOMOUNT),
        // The systemd adapter runs INSIDE the master container, whose dbus-over-TCP endpoint the
        // host probes over mDNS (avahi on the LAN). The container is NOT on the tailnet, so a bare
        // <cluster>-<node> does not resolve from the host (getaddrinfo fails before the port); the
        // .local FQDN avahi advertises does. Default to it so the probe reaches a running adapter.
        config.systemd().dbusHost().orElseGet(() -> clusterName + "-" + nodeName + ".local"),
        config.systemd().dbusPort().orElse(DEFAULT_SYSTEMD_ADAPTER_DBUS_PORT),
        config
            .hostAsset()
            .rotationRetentionCount()
            .orElse(DEFAULT_HOST_ASSET_ROTATION_RETENTION_COUNT),
        readinessOverridesFrom(config.readiness()),
        // No default: absent ⇒ no override — the boot keeps the Felix default and the generated pax
        // logback keeps its ${seed.log.level} default. A present value drives BOTH planes:
        // felix.log.level (Plane A) + the pax logback root via the seed.log.level property (Plane
        // B).
        config.logging().level());
  }

  /**
   * Project the operator's {@code rke2lab:readiness:} config into the neutral {@link
   * ReadinessOverrides} seam the host publishes for the readiness scions — the global default plus
   * the per-checkpoint map, each an {@link ReadinessDeadlineOverride} of two optional durations.
   * Absent everywhere ⇒ {@link ReadinessOverrides#NONE}, so every deadline stays the scenario's
   * {@code @ReadinessDeadlines} annotation default (formerly the dead {@code
   * DEFAULT_READINESS_TIMEOUT} this replaces — the deadline now lives in code, tuned here, not
   * defaulted here).
   */
  private static ReadinessOverrides readinessOverridesFrom(
      Rke2labConfig.ReadinessConfig readiness) {
    final ReadinessDeadlineOverride global =
        new ReadinessDeadlineOverride(readiness.connectTimeout(), readiness.timeout());
    final LinkedHashMap<String, ReadinessDeadlineOverride> perCheckpoint = new LinkedHashMap<>();
    readiness
        .checkpoints()
        .forEach(
            (slug, deadlines) ->
                perCheckpoint.put(
                    slug,
                    new ReadinessDeadlineOverride(
                        deadlines.connectTimeout(), deadlines.timeout())));
    return new ReadinessOverrides(global, Map.copyOf(perCheckpoint));
  }

  public String imageBuilderBinary() {
    return "nix";
  }

  public String netPrefix() {
    return "/net/" + host + ".local";
  }
}
