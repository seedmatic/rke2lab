package io.seedmatic.rke2lab.manifests.domain;

import io.seedmatic.rke2lab.manifests.ManifestsDomain;
import io.seedmatic.rke2lab.manifests.ManifestsDomainRegistrar;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.units.ingress.FunnelCertRestoreManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.ingress.FunnelStatePersistenceManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.ingress.IngressSystemNamespaceManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.ingress.TailnetPurgeManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.ingress.TailscaleManifestsUnit;
import java.util.List;
import org.osgi.service.component.annotations.Component;

/**
 * The public-door CAPABILITY: the Tailscale operator + the funnel machinery (cert restore / state
 * persistence / tailnet purge) that exposes a cluster's OWN endpoints publicly (the Flux receiver,
 * the PaC/Tekton webhook — each a funnel {@code Ingress} in its own domain that consumes this
 * operator). Distinct from {@code mesh} (Headscale/Headplane, the self-hosted control SERVICE):
 * ingress is a per-cluster capability, so it is STRUCTURAL on BOTH roles (see {@link
 * io.seedmatic.rke2lab.manifests.contract.ClusterRole}) — a management cluster exposes webhooks
 * too.
 *
 * <p>It owns the shared {@code ingress-system} namespace (the {@code
 * IngressSystemNamespaceManifestsUnit}, FOUNDATION layer) so it exists on every role; the
 * wrkld-only {@code mesh} domain {@code dependsOn} ingress for it, co-locating Headscale/Headplane
 * there.
 */
@Component(service = ManifestsDomainRegistrar.class)
public final class IngressDomainRegistrar implements ManifestsDomainRegistrar {

  @Override
  public ManifestsDomain domain() {
    return new ManifestsDomain(
        ManifestDomainCatalog.INGRESS,
        List.of(),
        List.of(
            new IngressSystemNamespaceManifestsUnit(),
            new FunnelCertRestoreManifestsUnit(),
            new TailnetPurgeManifestsUnit(),
            new TailscaleManifestsUnit(),
            new FunnelStatePersistenceManifestsUnit()));
  }
}
