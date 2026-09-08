package io.seedmatic.rke2lab.manifests.domain;

import io.seedmatic.rke2lab.manifests.ManifestsDomain;
import io.seedmatic.rke2lab.manifests.ManifestsDomainRegistrar;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.units.mesh.HeadplaneManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.mesh.HeadscaleManifestsUnit;
import java.util.List;
import org.osgi.service.component.annotations.Component;

/**
 * The self-hosted mesh CONTROL SERVICE (Headscale + Headplane) — the workload-plane tailnet. The
 * public-door capability (tailscale operator + funnel) is a separate {@code ingress} domain; the
 * {@code ingress-system} namespace is owned there (both roles carry ingress), so this domain {@code
 * dependsOn} ingress for it. mesh is WRKLD-only (see {@link
 * io.seedmatic.rke2lab.manifests.contract.ClusterRole}): the always-live workload cluster hosts the
 * single-writer Headscale, never the on-demand management cluster.
 */
@Component(service = ManifestsDomainRegistrar.class)
public final class MeshDomainRegistrar implements ManifestsDomainRegistrar {

  @Override
  public ManifestsDomain domain() {
    return new ManifestsDomain(
        ManifestDomainCatalog.MESH,
        List.of(ManifestDomainCatalog.INGRESS),
        List.of(new HeadscaleManifestsUnit(), new HeadplaneManifestsUnit()));
  }
}
