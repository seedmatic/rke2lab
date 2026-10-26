package io.seedmatic.rke2lab.clusterpki.contract;

import io.seedmatic.rke2lab.domain.annotations.Transitional;
import java.util.List;
import java.util.Optional;

/**
 * The vclusters the fleet runs, each on its host cluster, in ONE place until rke2lab's vcluster
 * declaration exists: the seal mints a PKI for each vcluster a sealed cluster hosts, the render
 * poses its {@code <name>-certs} Secret in {@code namespace} on its host's branch, and the host
 * adds its operator context.
 */
@Transitional(to = "the vcluster declaration", spec = "deterministic-cluster-access.adoc")
public record VclusterDeclaration(List<Vcluster> vclusters) {

  /** One vcluster, its namespace and the cluster that runs it. */
  public record Vcluster(String name, String namespace, String hostCluster) {}

  /** ccr's vcluster on nikopol-wrkld (the operator, 2026-10-26). */
  public static VclusterDeclaration fleet() {
    return new VclusterDeclaration(List.of(new Vcluster("ccr", "vcluster-ccr", "nikopol-wrkld")));
  }

  /** The names of the vclusters hosted by any of {@code clusters}, in declaration order. */
  public List<String> hostedBy(List<String> clusters) {
    return vclusters.stream()
        .filter(vcluster -> clusters.contains(vcluster.hostCluster()))
        .map(Vcluster::name)
        .toList();
  }

  /** The vcluster named {@code name}, if declared. */
  public Optional<Vcluster> named(String name) {
    return vclusters.stream().filter(vcluster -> vcluster.name().equals(name)).findFirst();
  }
}
