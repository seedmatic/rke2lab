package io.seedmatic.rke2lab.manifests.units.clusterapi;

import io.seedmatic.rke2lab.clusterpki.contract.VclusterDeclaration;
import io.seedmatic.rke2lab.clusterpki.contract.VclusterPki;
import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

/**
 * Renders each vcluster's {@code <name>-certs} Secret — its autonomous roots and service-account
 * pair ({@link VclusterPki}) — in its namespace, on its HOST cluster's own branch: vcluster reads
 * that Secret at start, keeps every CA and service-account key it finds and mints its leaves from
 * them. Only the plane that IS the host renders it (☸️: the vcluster lives on nikopol-wrkld's
 * branch), never its adopter, and never NODE_BOOTSTRAP: the roots belong to the vcluster's API, not
 * to any node's disk. RenderedSecretSeal seals it, as every rendered Secret, before the branch is
 * staged.
 *
 * <p>Absent material (a survey, a secret-blind render) → nothing is rendered, and the vcluster
 * waits for it: it would otherwise generate a PKI of its own, which no operator context trusts.
 */
public final class VclusterCertsManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID =
      ManifestDomainCatalog.CLUSTER_API + "/vcluster-certs";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile(ManifestDomainCatalog.CLUSTER_API, "vcluster-certs");

  public VclusterCertsManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    final ManifestSynthesisContext synth = ManifestSynthesisContext.current();
    final String self = synth.bootstrapIdentity().clusterName();
    final Optional<VclusterPki> pki = synth.vclusterPki();
    if (pki.isEmpty()) {
      return;
    }
    for (final VclusterDeclaration.Vcluster vcluster : VclusterDeclaration.fleet().vclusters()) {
      if (!vcluster.hostCluster().equals(self)) {
        continue;
      }
      pki.orElseThrow()
          .forVcluster(vcluster.name())
          .ifPresent(entry -> renderCerts(scope, vcluster, entry));
    }
  }

  private void renderCerts(
      final Construct scope,
      final VclusterDeclaration.Vcluster vcluster,
      final VclusterPki.Entry entry) {
    final ApiObject secret =
        new ApiObject(
            scope,
            "secret-" + entry.secretName(),
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Secret")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(entry.secretName())
                        .namespace(vcluster.namespace())
                        .annotations(packageProfile.packageAnnotations(Map.of()))
                        .build())
                .build());
    secret.addJsonPatch(JsonPatch.add("/type", "Opaque"));
    final Map<String, String> data = new LinkedHashMap<>();
    entry
        .secretData()
        .forEach(
            (key, pem) ->
                data.put(
                    key, Base64.getEncoder().encodeToString(pem.getBytes(StandardCharsets.UTF_8))));
    secret.addJsonPatch(JsonPatch.add("/data", data));
  }
}
