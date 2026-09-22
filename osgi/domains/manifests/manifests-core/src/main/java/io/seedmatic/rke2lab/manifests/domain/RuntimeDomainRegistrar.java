package io.seedmatic.rke2lab.manifests.domain;

import io.seedmatic.rke2lab.manifests.ManifestsDomain;
import io.seedmatic.rke2lab.manifests.ManifestsDomainRegistrar;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.units.runtime.SeedInclusterManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.runtime.flox.FloxCatalogManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.runtime.flox.FloxControllerManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.runtime.flox.FloxEnvManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.runtime.flox.FloxWebhookManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.runtime.rke2.RuntimeRke2ConfigManifestsUnit;
import java.util.List;
import org.osgi.service.component.annotations.Component;

@Component(service = ManifestsDomainRegistrar.class)
public final class RuntimeDomainRegistrar implements ManifestsDomainRegistrar {

  @Override
  public ManifestsDomain domain() {
    return new ManifestsDomain(
        ManifestDomainCatalog.RUNTIME,
        List.of(ManifestDomainCatalog.CLUSTER, ManifestDomainCatalog.PLATFORM),
        List.of(
            new RuntimeRke2ConfigManifestsUnit(),
            new SeedInclusterManifestsUnit(),
            new FloxControllerManifestsUnit(),
            new FloxCatalogManifestsUnit(),
            new FloxEnvManifestsUnit(),
            new FloxWebhookManifestsUnit()));
  }
}
