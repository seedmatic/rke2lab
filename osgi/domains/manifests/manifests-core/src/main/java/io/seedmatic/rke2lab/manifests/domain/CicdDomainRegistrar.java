package io.seedmatic.rke2lab.manifests.domain;

import io.seedmatic.rke2lab.manifests.ManifestsDomain;
import io.seedmatic.rke2lab.manifests.ManifestsDomainRegistrar;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.units.cicd.PacSecretManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.cicd.PacWebhookManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.cicd.RenderPipelineManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.cicd.RenderSigningSecretManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.cicd.RepositoryManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.cicd.TektonPipelinesManifestsUnit;
import java.util.List;
import org.osgi.service.component.annotations.Component;

@Component(service = ManifestsDomainRegistrar.class)
public final class CicdDomainRegistrar implements ManifestsDomainRegistrar {

  @Override
  public ManifestsDomain domain() {
    return new ManifestsDomain(
        ManifestDomainCatalog.CICD,
        List.of(),
        List.of(
            new TektonPipelinesManifestsUnit(),
            new RepositoryManifestsUnit(),
            new RenderPipelineManifestsUnit(),
            new RenderSigningSecretManifestsUnit(),
            new PacSecretManifestsUnit(),
            new PacWebhookManifestsUnit()));
  }
}
