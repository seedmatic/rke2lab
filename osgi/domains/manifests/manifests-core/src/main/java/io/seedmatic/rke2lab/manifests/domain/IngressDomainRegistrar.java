package io.seedmatic.rke2lab.manifests.domain;

import io.seedmatic.rke2lab.manifests.ManifestsDomain;
import io.seedmatic.rke2lab.manifests.ManifestsDomainRegistrar;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.units.mesh.FunnelCertRestoreManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.mesh.FunnelStatePersistenceManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.mesh.MeshSystemNamespaceManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.mesh.TailnetPurgeManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.mesh.TailscaleManifestsUnit;
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
 * <p>It owns the shared {@code mesh-system} namespace (the {@code
 * MeshSystemNamespaceManifestsUnit}, FOUNDATION layer) so it exists on every role; the wrkld-only
 * {@code mesh} domain {@code dependsOn} ingress for it. The namespace's k8s name stays {@code
 * mesh-system} for now (a rename would relocate the persisted funnel cert + Headscale state —
 * deferred). The units still live in the {@code units/mesh} package (a package/domain split to tidy
 * later).
 */
@Component(service = ManifestsDomainRegistrar.class)
public final class IngressDomainRegistrar implements ManifestsDomainRegistrar {

  @Override
  public ManifestsDomain domain() {
    return new ManifestsDomain(
        ManifestDomainCatalog.INGRESS,
        List.of(),
        List.of(
            new MeshSystemNamespaceManifestsUnit(),
            new FunnelCertRestoreManifestsUnit(),
            new TailnetPurgeManifestsUnit(),
            new TailscaleManifestsUnit(),
            new FunnelStatePersistenceManifestsUnit()));
  }
}
