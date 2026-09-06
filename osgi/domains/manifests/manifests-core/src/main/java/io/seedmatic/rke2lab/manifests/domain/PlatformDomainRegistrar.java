package io.seedmatic.rke2lab.manifests.domain;

import io.seedmatic.rke2lab.manifests.ManifestsDomain;
import io.seedmatic.rke2lab.manifests.ManifestsDomainRegistrar;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.units.platform.CertManagerManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.platform.ClusterIssuerManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.platform.ReplicatorManifestsUnit;
import java.util.List;
import org.osgi.service.component.annotations.Component;

@Component(service = ManifestsDomainRegistrar.class)
public final class PlatformDomainRegistrar implements ManifestsDomainRegistrar {

  @Override
  public ManifestsDomain domain() {
    return new ManifestsDomain(
        ManifestDomainCatalog.PLATFORM,
        List.of(),
        List.of(
            new CertManagerManifestsUnit(),
            new ClusterIssuerManifestsUnit(),
            new ReplicatorManifestsUnit()));
  }
}
