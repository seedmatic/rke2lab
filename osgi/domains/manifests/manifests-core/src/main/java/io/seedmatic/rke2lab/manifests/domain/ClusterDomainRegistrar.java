package io.seedmatic.rke2lab.manifests.domain;

import io.seedmatic.rke2lab.manifests.ManifestsDomain;
import io.seedmatic.rke2lab.manifests.ManifestsDomainRegistrar;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.units.cluster.ClusterRuntimeNamespaceManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.cluster.SessionViewerManifestsUnit;
import java.util.List;
import org.osgi.service.component.annotations.Component;

@Component(service = ManifestsDomainRegistrar.class)
public final class ClusterDomainRegistrar implements ManifestsDomainRegistrar {

  @Override
  public ManifestsDomain domain() {
    return new ManifestsDomain(
        ManifestDomainCatalog.CLUSTER,
        List.of(),
        List.of(new ClusterRuntimeNamespaceManifestsUnit(), new SessionViewerManifestsUnit()));
  }
}
