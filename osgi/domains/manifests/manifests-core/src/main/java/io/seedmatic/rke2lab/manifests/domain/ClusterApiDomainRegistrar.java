package io.seedmatic.rke2lab.manifests.domain;

import io.seedmatic.rke2lab.manifests.ManifestsDomain;
import io.seedmatic.rke2lab.manifests.ManifestsDomainRegistrar;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.units.clusterapi.ClusterApiOperatorManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.clusterapi.ClusterKubeconfigManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.clusterapi.ImageStateConfigMapManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.clusterapi.IncusIdentitySecretManifestsUnit;
import java.util.List;
import org.osgi.service.component.annotations.Component;

@Component(service = ManifestsDomainRegistrar.class)
public final class ClusterApiDomainRegistrar implements ManifestsDomainRegistrar {

  @Override
  public ManifestsDomain domain() {
    return new ManifestsDomain(
        ManifestDomainCatalog.CLUSTER_API,
        List.of(ManifestDomainCatalog.PLATFORM),
        List.of(
            new IncusIdentitySecretManifestsUnit(),
            new ImageStateConfigMapManifestsUnit(),
            new ClusterApiOperatorManifestsUnit(),
            new ClusterKubeconfigManifestsUnit()));
  }
}
