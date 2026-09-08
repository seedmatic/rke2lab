// @codebase
package io.seedmatic.rke2lab.manifests.units.ingress;

import io.seedmatic.rke2lab.manifests.refs.NamespaceRef;

/** Shared ingress references consumable independently of resource realization. */
public final class IngressRefs {

  /**
   * The system namespace the ingress capability (Tailscale operator + funnel) and the mesh control
   * service (Headscale/Headplane) share. Owned by the ingress domain (structural on both roles);
   * the wrkld-only mesh domain depends on ingress for it.
   */
  public static final NamespaceRef SYSTEM_NAMESPACE =
      NamespaceRef.of("ingress/system-namespace", "ingress-system");

  private IngressRefs() {}
}
