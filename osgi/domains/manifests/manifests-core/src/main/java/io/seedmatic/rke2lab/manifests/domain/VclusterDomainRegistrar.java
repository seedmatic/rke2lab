package io.seedmatic.rke2lab.manifests.domain;

import io.seedmatic.rke2lab.manifests.ManifestsDomain;
import io.seedmatic.rke2lab.manifests.ManifestsDomainRegistrar;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.units.clusterapi.VclusterCertsManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.clusterapi.VclusterManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.clusterapi.VclusterNamespaceManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.clusterapi.VclusterPinManifestsUnit;
import java.util.List;
import org.osgi.service.component.annotations.Component;

/**
 * The vclusters a cluster hosts: their PKI, the pin of their pods to their pool, the vcluster
 * itself. A domain of its own because its host is whichever cluster VclusterDeclaration names, a
 * workload cluster for ccr, while cluster-api renders on management clusters only. Each unit
 * renders nothing on a cluster that hosts no vcluster.
 */
@Component(service = ManifestsDomainRegistrar.class)
public final class VclusterDomainRegistrar implements ManifestsDomainRegistrar {

  @Override
  public ManifestsDomain domain() {
    return new ManifestsDomain(
        ManifestDomainCatalog.VCLUSTER,
        List.of(ManifestDomainCatalog.PLATFORM),
        List.of(
            new VclusterNamespaceManifestsUnit(),
            new VclusterCertsManifestsUnit(),
            new VclusterPinManifestsUnit(),
            new VclusterManifestsUnit()));
  }
}
