package io.seedmatic.rke2lab.dataplan.contract;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The canonical cluster ZFS dataset LAYOUT — the single source of truth for the datasets a node
 * needs so a cluster can be grown on it, under the pool root {@code tank/rke2lab}. Two natures: the
 * per-node EPHEMERAL tree ({@code control-nodes/<node>} + its {@code containerd} child, which backs
 * the openebs-zfs {@code pvc-*} volumes, GC'd on grow) and the cross-grow PERSIST tree ({@code
 * persist/*} — the Tailscale funnel cert state + the render maven cache, retained; a sibling of
 * {@code control-nodes} so the ephemeral wipe never reaches it).
 *
 * <p>Consumed identically by three parties: ndh materialises it on the host {@code tank} pool via
 * disko (pulled through {@code lib.dataplan} into {@code catalog.datasets}); {@code
 * OpenebsZfsManifestsUnit} names its StorageClass pools from it; the static persist PV names its
 * {@code volumeHandle} from it. DECLARED here (a pure factory), not derived from a network fact — a
 * storage twin of {@code ClusterNetworkBlueprint}, decomposed by nature.
 */
public record DataplanLayout(String pool, List<Dataset> datasets) {

  /** The ZFS pool the cluster layout lives under. */
  public static final String POOL = "tank";

  /** The layout root under the pool — the project (rke2lab), not the rke2 distro. */
  public static final String ROOT = "rke2lab";

  /** The control-plane node names whose ephemeral datasets back containerd. */
  public static final List<String> CONTROL_NODES = List.of("master", "peer1", "peer2", "peer3");

  /** The Tailscale funnel-state persist dataset — the openebs volumeHandle the funnel PV binds. */
  public static final String FUNNEL_CERT = "funnel-cert";

  /**
   * The cross-grow persist datasets (retained; out of the GC'd control-nodes tree). Only {@code
   * funnel-cert} today: the render maven-cache is currently EPHEMERAL (its PVC rides {@code
   * openebs-zfs-shared} on the control-nodes pool), and its durable form is PER-CLUSTER ({@code
   * tank/rke2lab/<cluster>/persist/maven-cache} — the Maven local repo is not concurrency-safe), a
   * foundation-3 item. A flat {@code persist/maven-cache} here was an unadopted orphan, so it is
   * not declared.
   */
  public static final List<String> PERSIST_DATASETS = List.of(FUNNEL_CERT);

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
   * Build the canonical layout: the {@code rke2lab} + {@code rke2lab/control-nodes} parents, each
   * control node's dataset + its {@code containerd} legacy child, and the {@code rke2lab/persist}
   * parent + its retained datasets. Behaviour-preserving vs the former static ndh literal (the
   * containerd children keep {@code mountpoint=legacy}); the persist tier is new and mounted by the
   * openebs-zfs CSI, so its datasets are {@code legacy} too — never host-auto-mounted.
   */
  public static DataplanLayout canonical() {
    final List<Dataset> datasets = new ArrayList<>();
    datasets.add(Dataset.fs(ROOT));
    datasets.add(Dataset.fs(ROOT + "/control-nodes"));
    for (final String node : CONTROL_NODES) {
      datasets.add(Dataset.fs(ROOT + "/control-nodes/" + node));
      datasets.add(Dataset.legacy(ROOT + "/control-nodes/" + node + "/containerd"));
    }
    datasets.add(Dataset.fs(ROOT + "/persist"));
    for (final String name : PERSIST_DATASETS) {
      datasets.add(Dataset.legacy(ROOT + "/persist/" + name));
    }
    return new DataplanLayout(POOL, List.copyOf(datasets));
  }

  /**
   * The absolute ZFS path a control node's ephemeral datasets (openebs {@code pvc-*}) live under.
   */
  public String controlNodePool(final String node) {
    return POOL + "/" + ROOT + "/control-nodes/" + node;
  }

  /**
   * The absolute ZFS path of the persist parent — the openebs {@code persist} StorageClass pool.
   */
  public String persistPool() {
    return POOL + "/" + ROOT + "/persist";
  }

  /** The absolute ZFS path of a named persist dataset — a static PV {@code volumeHandle} source. */
  public String persistDataset(final String name) {
    return persistPool() + "/" + name;
  }
}
