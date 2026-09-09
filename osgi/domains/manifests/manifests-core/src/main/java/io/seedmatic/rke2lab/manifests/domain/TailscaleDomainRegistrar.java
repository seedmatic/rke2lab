package io.seedmatic.rke2lab.manifests.domain;

import io.seedmatic.rke2lab.manifests.ManifestsDomain;
import io.seedmatic.rke2lab.manifests.ManifestsDomainRegistrar;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.units.tailscale.FunnelCertRestoreManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.tailscale.FunnelStatePersistenceManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.tailscale.TailnetPurgeManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.tailscale.TailscaleManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.tailscale.TailscaleSystemNamespaceManifestsUnit;
import java.util.List;
import org.osgi.service.component.annotations.Component;

/**
 * The per-cluster tailnet SUBSTRATE: the Tailscale operator, the subnet-router Connector (it
 * advertises the cluster's CIDRs to the tailnet), and the funnel machinery (cert restore / state
 * persistence / tailnet purge) that exposes a cluster's OWN endpoints publicly (the Flux receiver,
 * the PaC/Tekton webhook — each a funnel {@code Ingress} in its own domain that consumes this
 * operator). Broader than ingress — the funnel is one capability the operator provides; the real
 * in-cluster ingress is EnvoyGateway (networking domain). Distinct from {@code mesh}
 * (Headscale/Headplane, the self-hosted control SERVICE): tailscale is a per-cluster capability, so
 * it is STRUCTURAL on BOTH roles (see {@link io.seedmatic.rke2lab.manifests.contract.ClusterRole})
 * — a management cluster exposes webhooks and advertises subnets too.
 *
 * <p>It owns the {@code tailscale-system} namespace ({@code TailscaleSystemNamespaceManifestsUnit},
 * FOUNDATION layer). The mesh domain owns its OWN {@code mesh-system} namespace — the two no longer
 * share one.
 */
@Component(service = ManifestsDomainRegistrar.class)
public final class TailscaleDomainRegistrar implements ManifestsDomainRegistrar {

  @Override
  public ManifestsDomain domain() {
    return new ManifestsDomain(
        ManifestDomainCatalog.TAILSCALE,
        List.of(),
        List.of(
            new TailscaleSystemNamespaceManifestsUnit(),
            new FunnelCertRestoreManifestsUnit(),
            new TailnetPurgeManifestsUnit(),
            new TailscaleManifestsUnit(),
            new FunnelStatePersistenceManifestsUnit()));
  }
}
