// @codebase
package io.seedmatic.rke2lab.manifests.units.cluster;

import io.seedmatic.rke2lab.manifests.refs.NamespaceRef;

/** Shared cluster-owned references that may be consumed before resources are realized. */
public final class ClusterRefs {

  public static final NamespaceRef RUNTIME_SYSTEM_NAMESPACE =
      NamespaceRef.of("cluster/runtime-system-namespace", "rke2lab-system");

  /**
   * The secrets hinge namespace — where the operator's source credentials land and the mittwald
   * replicator fans them out from (and, at the workload phase, where ESO ingresses). A dedicated
   * datasource namespace, NOT {@code rke2lab-system}: the cross-cluster reader (the workload's ESO
   * ServiceAccount) is scoped to this alone, so it never gains read over the system grab-bag.
   */
  public static final String SECRETS_NAMESPACE = "rke2lab-secrets";

  private ClusterRefs() {}
}
