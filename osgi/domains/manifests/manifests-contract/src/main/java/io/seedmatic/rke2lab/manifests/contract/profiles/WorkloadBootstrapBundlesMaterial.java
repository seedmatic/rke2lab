package io.seedmatic.rke2lab.manifests.contract.profiles;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The node-side bootstrap bundle of each workload target, produced by the manager's PER-TARGET
 * render pass and consumed by its own (management) pass — the one material that does NOT come from
 * the cellar. Every other slice on the request is revealed from a seal; this one is carved fresh,
 * in the same run, by the target's {@code ClusterRole.WRKLD} synthesis: the exploder routes that
 * pass's {@code NODE_BOOTSTRAP}-marked resources out of the workload branch into {@code
 * .bootstrap/rke2lab-bootstrap.yaml}, and the pass hands the multi-doc YAML here.
 *
 * <p>{@code ClusterApiWorkloadManifestsUnit} looks up the {@link Entry} for each target and renders
 * it as the {@code <cluster>-server-manifests} Secret in {@code rke2lab-<cluster>} — the Secret
 * {@code seed-incluster} references from {@code RKE2ControlPlane.spec.files[].contentFrom} so a
 * node CAPRKE2 provisions receives the bundle through cloud-init (the second poser; a host-grown
 * node gets the same artifact over devlxd from the cellar instead). Absence — no target, or a pass
 * that marked nothing node-bootstrap — is an empty {@code Optional} on the context, never a
 * placeholder: an empty Secret would satisfy the reconciler's material gate with a bundle that
 * boots no CNI.
 */
public record WorkloadBootstrapBundlesMaterial(List<Entry> entries) {

  public WorkloadBootstrapBundlesMaterial {
    entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
  }

  /** The bundle for {@code clusterName}, if this material carries one. */
  public Optional<Entry> forCluster(String clusterName) {
    return entries.stream().filter(entry -> entry.clusterName().equals(clusterName)).findFirst();
  }

  /**
   * One target's bundle: the CAPI {@code Cluster} name it belongs to, and the multi-doc YAML the
   * exploder carved out of that cluster's rendered branch.
   */
  public record Entry(String clusterName, String manifests) {}
}
