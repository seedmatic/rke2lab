package io.seedmatic.rke2lab.manifests.units.clusterapi;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.contract.ManifestLayer;
import io.seedmatic.rke2lab.manifests.contract.profiles.TlsAuthorityCaMaterial;
import io.seedmatic.rke2lab.manifests.ingress.Component;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import io.seedmatic.rke2lab.manifests.units.platform.ClusterIssuerManifestsUnit;
import io.seedmatic.rke2lab.manifests.upstream.UpstreamYamlInclusion;
import io.seedmatic.rke2lab.manifests.upstream.UpstreamYamlInclusion.UpstreamRewrite;
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

  private static final String CAPN_NAMESPACE = "capn-system";

  /**
   * The CAPN provider's container, read off {@code infrastructure-components.yaml} for the pinned
   * release: one Deployment {@code capn-controller-manager} with one container {@code manager}.
   * Only the CONTAINER is named here, because {@code spec.deployment} keys on it and the operator
   * supplies the Deployment — naming the Deployment too is what {@code spec.patches} would have
   * required, and that is the coupling this avoids.
   */
  private static final String CAPN_CONTAINER = "manager";

  private static final String TRUST_ANCHOR_SECRET = "rke2lab-tls-authority-ca";

  private static final String TRUST_ANCHOR_VOLUME = "rke2lab-tls-authority-ca";

  private static final String TRUST_ANCHOR_MOUNT = "/etc/rke2lab/ca";

  private static final String PROVIDER_CONFIG_SECRET = "capn-provider-config";

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
                        .namespace(CAPN_NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "operator.cluster.x-k8s.io|InfrastructureProvider|capn-system|incus"))
                        .build())
                .build());

    final Map<String, Object> spec = new LinkedHashMap<>();
    // The CAPI operator resolves a non-clusterctl provider from a GitHub release URL that must
    // point at the components file itself (…/releases/<tag>/infrastructure-components.yaml); it
    // reads metadata.yaml from the same release. A bare …/releases base is rejected.
    spec.put("version", version);
    spec.put(
        "fetchConfig",
        Map.of(
            "url",
            "https://github.com/lxc/cluster-api-provider-incus/releases/"
                + version
                + "/infrastructure-components.yaml"));

    ManifestSynthesisContext.current()
        .tlsAuthorityCa()
        .ifPresent(
            material -> {
              renderTrustAnchorSecret(scope, material);
              renderProviderConfigSecret(scope);
              // The CA must reach the provider POD's trust store, and the two halves come from two
              // different seams because neither can do both:
              //
              //  * the VOLUME goes through `configSecret`. CAPN's own components file declares
              //    `volumes: ${CAPN_VOLUMES:=[]}` and `volumeMounts: ${CAPN_VOLUME_MOUNTS:=[]}`,
              // and
              //    the operator substitutes those variables from that Secret — a declared extension
              //    point, so it survives an upstream rename of the Deployment. `spec.patches` would
              //    also work and is strictly worse: a strategic merge keyed on
              //    `capn-controller-manager`/`manager` goes SILENTLY inert the day either name
              // moves.
              //  * the ENV goes through `spec.deployment`, because CAPN declares no variable for it
              //    and `deployment.containers[]` carries `env` but neither `volumes` nor
              //    `volumeMounts` (checked against the v1alpha2 schema).
              //
              // `SSL_CERT_DIR` and not `SSL_CERT_FILE`: Go REPLACES the dir list with this value,
              // so
              // naming `/etc/ssl/certs` alongside ours keeps the image's own store, whereas
              // SSL_CERT_FILE would replace the system bundle outright.
              spec.put(
                  "configSecret",
                  Map.of("name", PROVIDER_CONFIG_SECRET, "namespace", CAPN_NAMESPACE));
              spec.put(
                  "deployment",
                  Map.of(
                      "containers",
                      List.of(
                          Map.of(
                              "name",
                              CAPN_CONTAINER,
                              "env",
                              List.of(
                                  Map.of(
                                      "name",
                                      "SSL_CERT_DIR",
                                      "value",
                                      "/etc/ssl/certs:" + TRUST_ANCHOR_MOUNT))))));
            });

    provider.addJsonPatch(JsonPatch.add("/spec", spec));
  }

  /**
   * The fleet's TLS authority as a plain CA bundle in {@code capn-system}.
   *
   * <p>On the ORDINARY branch, unlike {@link ClusterIssuerManifestsUnit}'s CA Secret which rides
   * the durable NODE_BOOTSTRAP lane: that one carries a private key, this one carries none. A CA
   * certificate is public by construction, so Flux may apply it like any other manifest and a
   * rotation is a normal reconcile rather than a node-side pose.
   */
  private void renderTrustAnchorSecret(
      final Construct scope, final TlsAuthorityCaMaterial material) {
    final ApiObject secret =
        new ApiObject(
            scope,
            "secret-" + TRUST_ANCHOR_SECRET,
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Secret")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(TRUST_ANCHOR_SECRET)
                        .namespace(CAPN_NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|Secret|" + CAPN_NAMESPACE + "|" + TRUST_ANCHOR_SECRET))
                        .build())
                .build());
    secret.addJsonPatch(JsonPatch.add("/data", Map.of("ca.crt", base64(material.caCertPem()))));
  }

  /**
   * The provider's substitution variables — the volume + mount that put {@link
   * #TRUST_ANCHOR_SECRET} on the provider pod's filesystem.
   *
   * <p>⚠️ Both values are SINGLE-LINE flow-style JSON, deliberately. The operator substitutes these
   * TEXTUALLY into the components YAML at {@code volumes: ${CAPN_VOLUMES:=[]}}, so a multi-line
   * block would land at the wrong indentation and break the document rather than the field.
   *
   * <p>The operator treats a configSecret's contents as IMMUTABLE — to change them you rename the
   * Secret. That is affordable here only because these two values are static: they NAME the CA
   * Secret rather than carrying the CA, so rotating the authority changes {@link
   * #TRUST_ANCHOR_SECRET}'s content and leaves this one untouched.
   */
  private void renderProviderConfigSecret(final Construct scope) {
    final ApiObject secret =
        new ApiObject(
            scope,
            "secret-" + PROVIDER_CONFIG_SECRET,
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Secret")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(PROVIDER_CONFIG_SECRET)
                        .namespace(CAPN_NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|Secret|" + CAPN_NAMESPACE + "|" + PROVIDER_CONFIG_SECRET))
                        .build())
                .build());
    secret.addJsonPatch(
        JsonPatch.add(
            "/stringData",
            Map.of(
                "CAPN_VOLUMES",
                "[{\"name\":\""
                    + TRUST_ANCHOR_VOLUME
                    + "\",\"secret\":{\"secretName\":\""
                    + TRUST_ANCHOR_SECRET
                    + "\"}}]",
                "CAPN_VOLUME_MOUNTS",
                "[{\"name\":\""
                    + TRUST_ANCHOR_VOLUME
                    + "\",\"mountPath\":\""
                    + TRUST_ANCHOR_MOUNT
                    + "\",\"readOnly\":true}]")));
  }

  private static String base64(final String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
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
