package io.seedmatic.rke2lab.manifests.domain;

import io.seedmatic.rke2lab.manifests.ManifestsDomain;
import io.seedmatic.rke2lab.manifests.ManifestsDomainRegistrar;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.units.clusterapi.ClusterApiCrRenderer;
import io.seedmatic.rke2lab.manifests.units.clusterapi.ClusterApiManagementManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.clusterapi.ClusterApiOperatorManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.clusterapi.ClusterApiWorkloadManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.clusterapi.ClusterKubeconfigManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.clusterapi.ImageStateConfigMapManifestsUnit;
import java.util.List;
import org.osgi.service.component.annotations.Component;

@Component(service = ManifestsDomainRegistrar.class)
public final class ClusterApiDomainRegistrar implements ManifestsDomainRegistrar {

  @Override
  public ManifestsDomain domain() {
    // The shared CAPI CR builders, constructed ONCE and handed to the units that delegate to them
    // (composition, not a static helper): the workload greenfield unit and, next, the mgmt-adoption
    // unit both build the common Cluster/LXCCluster/RKE2ControlPlane/BYO-CA objects through it.
    final ClusterApiCrRenderer renderer = new ClusterApiCrRenderer();
    return new ManifestsDomain(
        ManifestDomainCatalog.CLUSTER_API,
        List.of(ManifestDomainCatalog.PLATFORM),
        List.of(
            new ImageStateConfigMapManifestsUnit(),
            new ClusterApiOperatorManifestsUnit(),
            new ClusterKubeconfigManifestsUnit(),
            new ClusterApiManagementManifestsUnit(renderer),
            new ClusterApiWorkloadManifestsUnit(renderer)));
  }
}
