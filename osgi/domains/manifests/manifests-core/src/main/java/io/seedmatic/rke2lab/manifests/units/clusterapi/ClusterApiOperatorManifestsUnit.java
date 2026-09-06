package io.seedmatic.rke2lab.manifests.units.clusterapi;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.contract.ManifestLayer;
import io.seedmatic.rke2lab.manifests.ingress.Component;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import io.seedmatic.rke2lab.manifests.units.platform.ClusterIssuerManifestsUnit;
import io.seedmatic.rke2lab.manifests.upstream.UpstreamYamlInclusion;
import io.seedmatic.rke2lab.manifests.upstream.UpstreamYamlInclusion.UpstreamRewrite;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class ClusterApiOperatorManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.CLUSTER_API + "/operator";

  /** Exploded package dir (relative to the cluster-api domain); diverges from the id segment. */
  public static final String OUTPUT_DIR = "cluster-api-operator";

  // The operator install + provider CRs register the CAPI/CAPN/CAPRKE2 CRDs at runtime → operators
  // layer, so any workload CR that targets those CRDs dry-runs only after this layer is healthy.
  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile(
          ManifestDomainCatalog.CLUSTER_API, OUTPUT_DIR, false, ManifestLayer.OPERATORS);

  /**
   * The upstream operator's self-signed webhook Issuer + serving Certificate we repoint at our CA.
   */
  private static final String UPSTREAM_SELFSIGNED_ISSUER = "capi-operator-selfsigned-issuer";

  private static final String UPSTREAM_SERVING_CERT = "capi-operator-serving-cert";

  public ClusterApiOperatorManifestsUnit() {
    // dependsOn platform/cluster-issuer: the operator's serving Certificate now references the
    // shared rke2lab-ca ClusterIssuer, so Flux must apply that issuer (Ready) first.
    super(MANIFEST_UNIT_ID, List.of(ClusterIssuerManifestsUnit.MANIFEST_UNIT_ID));
  }

  @Override
  public String outputDir() {
    return OUTPUT_DIR;
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    final String operatorVersion =
        ManifestSynthesisContext.current().componentVersions().of(Component.CLUSTER_API_OPERATOR);
    final String coreVersion =
        ManifestSynthesisContext.current().componentVersions().of(Component.CAPI_CORE);
    final String incusProviderVersion =
        ManifestSynthesisContext.current().componentVersions().of(Component.CAPI_INCUS_PROVIDER);
    final String rke2ProviderVersion =
        ManifestSynthesisContext.current().componentVersions().of(Component.CAPI_RKE2_PROVIDER);

    final String operatorReleaseResource =
        "/upstream/clusterapi/operator/release-" + operatorVersion + ".yaml";
    new UpstreamYamlInclusion(
        scope, operatorReleaseResource, packageProfile, context.yaml(), new CaIssuerRewrite());

    createProviderNamespaces(scope);
    createCoreProvider(scope, coreVersion);
    createInfrastructureProvider(scope, incusProviderVersion);
    createControlPlaneProvider(scope, rke2ProviderVersion);
    createBootstrapProvider(scope, rke2ProviderVersion);
  }

  private void createProviderNamespaces(final Construct scope) {
    for (String namespace : new String[] {"capi-system", "capn-system", "caprke2-system"}) {
      new ApiObject(
          scope,
          "namespace-" + namespace,
          ApiObjectProps.builder()
              .apiVersion("v1")
              .kind("Namespace")
              .metadata(
                  ApiObjectMetadata.builder()
                      .name(namespace)
                      .annotations(packageProfile.packageAnnotations(namespace))
                      .build())
              .build());
    }
  }

  private void createCoreProvider(final Construct scope, final String version) {
    ApiObject provider =
        new ApiObject(
            scope,
            "coreprovider-cluster-api",
            ApiObjectProps.builder()
                .apiVersion("operator.cluster.x-k8s.io/v1alpha2")
                .kind("CoreProvider")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("cluster-api")
                        .namespace("capi-system")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "operator.cluster.x-k8s.io|CoreProvider|capi-system|cluster-api"))
                        .build())
                .build());

    provider.addJsonPatch(JsonPatch.add("/spec", Map.of("version", version)));
  }

  private void createInfrastructureProvider(final Construct scope, final String version) {
    ApiObject provider =
        new ApiObject(
            scope,
            "infrastructureprovider-incus",
            ApiObjectProps.builder()
                .apiVersion("operator.cluster.x-k8s.io/v1alpha2")
                .kind("InfrastructureProvider")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("incus")
                        .namespace("capn-system")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "operator.cluster.x-k8s.io|InfrastructureProvider|capn-system|incus"))
                        .build())
                .build());

    // The CAPI operator resolves a non-clusterctl provider from a GitHub release URL that must
    // point at the components file itself (…/releases/<tag>/infrastructure-components.yaml); it
    // reads metadata.yaml from the same release. A bare …/releases base is rejected.
    provider.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "version",
                version,
                "fetchConfig",
                Map.of(
                    "url",
                    "https://github.com/lxc/cluster-api-provider-incus/releases/"
                        + version
                        + "/infrastructure-components.yaml"))));
  }

  private void createControlPlaneProvider(final Construct scope, final String version) {
    ApiObject provider =
        new ApiObject(
            scope,
            "controlplaneprovider-rke2",
            ApiObjectProps.builder()
                .apiVersion("operator.cluster.x-k8s.io/v1alpha2")
                .kind("ControlPlaneProvider")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("rke2")
                        .namespace("caprke2-system")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "operator.cluster.x-k8s.io|ControlPlaneProvider|caprke2-system|rke2"))
                        .build())
                .build());

    provider.addJsonPatch(JsonPatch.add("/spec", Map.of("version", version)));
  }

  private void createBootstrapProvider(final Construct scope, final String version) {
    ApiObject provider =
        new ApiObject(
            scope,
            "bootstrapprovider-rke2",
            ApiObjectProps.builder()
                .apiVersion("operator.cluster.x-k8s.io/v1alpha2")
                .kind("BootstrapProvider")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("rke2")
                        .namespace("caprke2-system")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "operator.cluster.x-k8s.io|BootstrapProvider|caprke2-system|rke2"))
                        .build())
                .build());

    provider.addJsonPatch(JsonPatch.add("/spec", Map.of("version", version)));
  }

  /**
   * Repoints the operator's webhook serving cert at the shared cluster CA {@code ClusterIssuer}
   * ({@code rke2lab-ca}) so it chains to our own root like every other in-cluster leaf: DROP the
   * upstream self-signed {@code Issuer}, and rewrite the serving {@code Certificate}'s {@code
   * issuerRef} to the {@code ClusterIssuer}. The {@code cert-manager.io/inject-ca-from} on the
   * webhook configs is unchanged — ca-injector then injects OUR CA. Matches upstream by exact name;
   * a release bump that renames these would leave both untouched (the build still succeeds), so the
   * post-grow check is that the webhook configs carry the rke2lab-ca chain.
   */
  private static final class CaIssuerRewrite implements UpstreamRewrite {

    @Override
    public boolean accept(final Map<String, Object> document) {
      return !("Issuer".equals(document.get("kind"))
          && nameOf(document).filter(UPSTREAM_SELFSIGNED_ISSUER::equals).isPresent());
    }

    @Override
    public Map<String, Object> transform(final Map<String, Object> document) {
      if ("Certificate".equals(document.get("kind"))
          && nameOf(document).filter(UPSTREAM_SERVING_CERT::equals).isPresent()
          && document.get("spec") instanceof Map<?, ?> spec) {
        @SuppressWarnings("unchecked")
        final Map<String, Object> mutableSpec = (Map<String, Object>) spec;
        mutableSpec.put(
            "issuerRef",
            Map.of("kind", "ClusterIssuer", "name", ClusterIssuerManifestsUnit.ISSUER_NAME));
      }
      return document;
    }

    private static Optional<String> nameOf(final Map<String, Object> document) {
      return document.get("metadata") instanceof Map<?, ?> metadata
          ? Optional.ofNullable(metadata.get("name")).map(Object::toString)
          : Optional.empty();
    }
  }
}
