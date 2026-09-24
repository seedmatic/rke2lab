package io.seedmatic.rke2lab.dataplan.contract;

import io.seedmatic.rke2lab.manifests.contract.ClusterRole;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The canonical cluster ZFS dataset LAYOUT — the single source of truth for the datasets a node
 * needs so a cluster can be grown on it. <b>Every cluster gets its own ROOT</b> under {@code
 * tank/rke2lab}: {@code tank/rke2lab/<role>/{ephemeral,persist}}. Two natures live under it — the
 * EPHEMERAL tree ({@code ephemeral/volumes} for the openebs-zfs {@code pvc-*} and {@code
 * ephemeral/nodes} for each node's self-created {@code <node>/containerd}, wiped on grow) and the
 * cross-grow PERSIST tree ({@code persist/*} — the Tailscale funnel cert state, retained).
 *
 * <p>Consumed identically by three parties: ndh materialises it on the host {@code tank} pool via
 * disko (pulled through {@code lib.dataplan} into {@code catalog.datasets}); {@code
 * OpenebsZfsManifestsUnit} names its StorageClass pools from it; {@code nixos/containerd.nix}
 * mounts the snapshotter dataset under it. DECLARED here (a pure factory), not derived from a
 * network fact — a storage twin of {@code ClusterNetworkBlueprint}, decomposed by nature.
 *
 * <p><b>Why a root per cluster.</b> Two clusters on one host may share neither tier. For the
 * persist tier, sharing one funnel-cert dataset destroys the Let's Encrypt budget of BOTH clusters
 * at once (see {@code docs/architecture/cluster-api/pac-in-cluster-render-spec.adoc} §
 * funnel-per-cluster); for the ephemeral tier, one shared {@code poolname} made every cluster's
 * dynamic PVCs land in the MANAGEMENT node's dataset (measured 2026-09-21: the workload cluster's
 * {@code pvc-e13b83da-…} sat under {@code control-nodes/master/}). {@code persist} stays a SIBLING
 * of {@code ephemeral} inside the cluster root, so the "the ephemeral wipe never reaches persist"
 * invariant is still expressed at a single level, per cluster: the wipe is {@code zfs destroy -r
 * tank/rke2lab/<role>/ephemeral}. The cluster ROOT is deliberately NOT a destroy target — recursing
 * from there would take {@code persist} with it.
 *
 * <p><b>Why the root is keyed by {@link ClusterRole}, not by cluster name.</b> The projection ndh
 * consumes is ONE checked-in list materialised on EVERY host, so it cannot name hosts: a fleet
 * cluster roster here would be a second owner of a fact {@code ClusterNetworkBlueprint} already
 * derives. The role suffices, because a host carries exactly ONE cluster per role ({@code
 * clustersOnSameHost()} enumerates one per {@link ClusterRole}) — so {@code wrkld} is unambiguous
 * on any host, and {@link #canonical()} stays argument-free.
 *
 * <p><b>No node roster.</b> The former {@code CONTROL_NODES} pet list ({@code master}, {@code
 * peer1..3}) is gone. It only ever made sense for a HOST-grown cluster, a CAPI node's name is
 * random, and the live tree showed the declared pets as empty shells while the real nodes had made
 * their own datasets. {@code nixos/containerd.nix} creates its {@code <node>/containerd} dataset
 * with {@code zfs create -p} when absent, so the node owns that dataset — one owner. Only the
 * PARENTS are declared here, because openebs does NOT create its {@code poolname}: it must exist
 * for the CSI to create volume datasets under it.
 */
public record DataplanLayout(String pool, List<Dataset> datasets) {

  /** The ZFS pool the cluster layout lives under. */
  public static final String POOL = "tank";

  /** The layout root under the pool — the project (rke2lab), not the rke2 distro. */
  public static final String ROOT = "rke2lab";

  /** The Tailscale funnel-state persist dataset — the openebs volumeHandle the funnel PV binds. */
  public static final String FUNNEL_CERT = "funnel-cert";

  /**
   * The render pipeline's Maven cache root — the local repo plus the maven-build-cache, side by
   * side. It is a CACHE (carried across runs), not a workspace (scratch for one run), and the
   * difference is the whole point: as a Tekton workspace it dragged in the affinity assistant,
   * which co-mounts every PVC-backed workspace and so forced the {@code shared: yes} bind-mount
   * class onto the ephemeral tier — where a dynamic {@code pvc-<uuid>} leaked one dataset per cold
   * start (measured 2026-09-24: 8 datasets for 1 live claim) and a node-pinned PV stranded it on
   * the first control-plane roll. The render Task mounts it as a RAW VOLUME instead, exactly as the
   * nix store already did, so it is only ever mounted by the one step that writes it.
   */
  public static final String MAVEN_CACHE = "maven-cache";

  /**
   * The cross-grow persist datasets (retained; out of the wiped {@code ephemeral} tree), declared
   * once per cluster root. A flat {@code persist/maven-cache} used to sit here as an UNADOPTED
   * orphan — declared by no intention, bound by no PV; it is now adopted by the render pipeline's
   * {@code VolumeIntention}, which is what makes declaring it correct rather than litter.
   */
  public static final List<String> PERSIST_DATASETS = List.of(FUNNEL_CERT, MAVEN_CACHE);

  /** A single ZFS dataset request: a pool-relative path, its disko type, and its ZFS options. */
  public record Dataset(String path, String type, Map<String, String> options) {

    static Dataset fs(String path) {
      return new Dataset(path, "zfs_fs", Map.of());
    }

    /** A dataset the guest/CSI mounts, never the nerd host — {@code mountpoint=legacy}. */
    static Dataset legacy(String path) {
      return new Dataset(path, "zfs_fs", Map.of("mountpoint", "legacy"));
    }
  }

  /**
   * The nature tier wiped on a grow — {@code <role>/ephemeral} is THE destroy root, so no retained
   * dataset can ever sit under it by construction.
   */
  public static final String EPHEMERAL = "ephemeral";

  /** The ephemeral child each NODE fills with its own {@code <node>/containerd}. */
  public static final String NODES = "nodes";

  /** The ephemeral child openebs fills with the dynamic {@code pvc-*} volumes. */
  public static final String VOLUMES = "volumes";

  /** The nature tier retained across grows — a sibling of {@link #EPHEMERAL}, never under it. */
  public static final String PERSIST = "persist";

  /**
   * ONE cluster's view of the layout — every path that cluster's consumers name. Built from the
   * cluster NAME but keyed on its {@link ClusterRole}, so a caller never has to know that the
   * discriminator is the role (see the type javadoc for why it is).
   */
  public record ClusterDataplan(ClusterRole role) {

    /** This cluster's view of the layout, resolved from its {@code <host>-<role>} name. */
    public static ClusterDataplan of(final String clusterName) {
      return new ClusterDataplan(ClusterRole.of(clusterName));
    }

    /** This cluster's root — the subtree that holds ALL of its storage, both natures. */
    public String root() {
      return POOL + "/" + ROOT + "/" + role.token();
    }

    /**
     * The absolute ZFS path openebs creates this cluster's dynamic {@code pvc-*} under — the {@code
     * poolname} of its EPHEMERAL StorageClasses. Per-cluster: one shared pool made every cluster's
     * PVCs land in the management node's dataset.
     */
    public String ephemeralPool() {
      return root() + "/" + EPHEMERAL + "/" + VOLUMES;
    }

    /**
     * The absolute ZFS path a node's own {@code <node>/containerd} dataset lives under. The node
     * CREATES its child here ({@code zfs create -p}); only this parent is declared, because a
     * managed node's name is not knowable in advance.
     */
    public String nodesPool() {
      return root() + "/" + EPHEMERAL + "/" + NODES;
    }

    /**
     * The absolute ZFS path of this cluster's persist parent — the {@code poolname} of its openebs
     * {@code persist} StorageClass, and the {@code poolName} of its funnel {@code ZFSVolume}.
     */
    public String persistPool() {
      return root() + "/" + PERSIST;
    }

    /**
     * The absolute ZFS path of one named persist dataset of this cluster — the dataset a static PV
     * ADOPTS by stable name, the only handle that survives an etcd wipe.
     */
    public String persistDataset(final String name) {
      return persistPool() + "/" + name;
    }
  }

  /**
   * Build the canonical layout: the {@code rke2lab} parent, then per {@link ClusterRole} that
   * cluster's root and the PARENTS of its two natures — {@code ephemeral/{nodes,volumes}} and
   * {@code persist} + its retained datasets.
   *
   * <p>Only parents, because each dataset below one is created by the party that owns its content:
   * the node does {@code zfs create -p} for its {@code containerd} child, openebs creates the
   * {@code pvc-*} volumes. What openebs does NOT do is create its own {@code poolname}, which is
   * exactly why the parents must be declared. The retained datasets ARE declared, because a static
   * PV adopts one by stable name — that adoption is the only handle that survives an etcd wipe.
   *
   * <p>The persist datasets are {@code legacy}: they are mounted by the openebs-zfs CSI, never
   * host-auto-mounted. The parents are not — nothing mounts a parent.
   */
  public static DataplanLayout canonical() {
    final List<Dataset> datasets = new ArrayList<>();
    datasets.add(Dataset.fs(ROOT));
    for (final ClusterRole role : ClusterRole.values()) {
      final String clusterRoot = ROOT + "/" + role.token();
      datasets.add(Dataset.fs(clusterRoot));
      datasets.add(Dataset.fs(clusterRoot + "/" + EPHEMERAL));
      datasets.add(Dataset.fs(clusterRoot + "/" + EPHEMERAL + "/" + NODES));
      datasets.add(Dataset.fs(clusterRoot + "/" + EPHEMERAL + "/" + VOLUMES));
      datasets.add(Dataset.fs(clusterRoot + "/" + PERSIST));
      for (final String name : PERSIST_DATASETS) {
        datasets.add(Dataset.legacy(clusterRoot + "/" + PERSIST + "/" + name));
      }
    }
    return new DataplanLayout(POOL, List.copyOf(datasets));
  }
}
