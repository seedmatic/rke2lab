// @codebase
package io.seedmatic.rke2lab.manifests.units.tailscale;

import io.seedmatic.rke2lab.manifests.refs.NamespaceRef;

/** Shared tailscale references consumable independently of resource realization. */
public final class TailscaleRefs {

  /**
   * The system namespace the tailscale substrate lives in — the operator, the subnet-router
   * Connector and the funnel machinery (cert restore / state persistence / tailnet purge). Owned by
   * the tailscale domain (structural on both roles). The mesh control service (Headscale/Headplane)
   * owns its OWN {@code mesh-system} namespace — the two no longer share.
   */
  public static final NamespaceRef SYSTEM_NAMESPACE =
      NamespaceRef.of("tailscale/system-namespace", "tailscale-system");

  private TailscaleRefs() {}
}
