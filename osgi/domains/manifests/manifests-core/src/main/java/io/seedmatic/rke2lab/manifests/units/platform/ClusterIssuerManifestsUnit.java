package io.seedmatic.rke2lab.manifests.units.platform;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestAnnotation;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.contract.ManifestLayer;
import io.seedmatic.rke2lab.manifests.contract.profiles.ClusterIssuerCaMaterial;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

/**
 * Renders the cluster's single cert-manager {@code CA} {@code ClusterIssuer} ({@code rke2lab-ca})
 * and delivers its CA key-pair — the two-tier convergence that dissolves the per-webhook
 * self-signed issuers. Every in-cluster leaf {@code Certificate} (the flox webhook serving cert,
 * CAPI, future webhooks) references this one issuer, so all chain to our own cluster-pki root
 * (mammoth-skate).
 *
 * <p>Two resources, the two-doors split:
 *
 * <ul>
 *   <li><b>The {@code ClusterIssuer}</b> {@code rke2lab-ca} (kind {@code CA}, {@code
 *       spec.ca.secretName = rke2lab-ca-key-pair}) — cluster-scoped, carries no secret, renders
 *       UNCONDITIONALLY onto the branch. As a {@code cert-manager.io} CR it auto-depends on {@code
 *       platform/cert-manager} through the planner's derived CRD→CR edge, so it applies only once
 *       cert-manager is up.
 *   <li><b>The key-pair {@code Secret}</b> {@code rke2lab-ca-key-pair} (type {@code
 *       kubernetes.io/tls}, {@code tls.crt} = the CA chain to the root, {@code tls.key} = the CA
 *       private key) in {@code kube-system} (cert-manager's cluster-resource-namespace, where a
 *       {@code ClusterIssuer}'s {@code ca.secretName} is resolved). A real CA private key → it
 *       rides the durable NODE_BOOTSTRAP lane (seeded node-side over devlxd at the grow), NEVER on
 *       the reconciled branch: a secret-blind in-cluster render finds no material here and simply
 *       omits it, so nothing on the branch is stripped.
 * </ul>
 *
 * <p>The manifests scion reveals the cluster-pki {@code ClusterIssuerCa} from the cellar and
 * translates it to {@link ClusterIssuerCaMaterial} on the synthesis request — no {@code
 * cluster-pki} type crosses into manifests. Absent material (a bare survey / secret-blind render) →
 * only the {@code ClusterIssuer} renders.
 */
public final class ClusterIssuerManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.PLATFORM + "/cluster-issuer";

  /** The one CA issuer every in-cluster leaf chains to. */
  public static final String ISSUER_NAME = "rke2lab-ca";

  /** The CA key-pair Secret backing the issuer, in cert-manager's cluster-resource-namespace. */
  private static final String CA_SECRET_NAME = "rke2lab-ca-key-pair";

  private static final String CA_SECRET_NAMESPACE = "kube-system";

  // Same FOUNDATION layer as cert-manager: the issuer is cluster-wide infrastructure the operators
  // layer's webhook certs depend on.
  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("platform", "cluster-issuer", false, ManifestLayer.FOUNDATION);

  public ClusterIssuerManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    renderClusterIssuer(scope);
    ManifestSynthesisContext.current().clusterIssuerCa().ifPresent(m -> renderCaSecret(scope, m));
  }

  private void renderClusterIssuer(final Construct scope) {
    final ApiObject issuer =
        new ApiObject(
            scope,
            "clusterissuer-rke2lab-ca",
            ApiObjectProps.builder()
                .apiVersion("cert-manager.io/v1")
                .kind("ClusterIssuer")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(ISSUER_NAME)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "cert-manager.io|ClusterIssuer||" + ISSUER_NAME))
                        .build())
                .build());
    issuer.addJsonPatch(JsonPatch.add("/spec", Map.of("ca", Map.of("secretName", CA_SECRET_NAME))));
  }

  // The CA key-pair, delivered into kube-system via the NODE_BOOTSTRAP lane (node-side at grow),
  // never on the reconciled branch — a secret-blind render omits it (no material) rather than
  // stripping it.
  private void renderCaSecret(final Construct scope, final ClusterIssuerCaMaterial material) {
    final ApiObject secret =
        new ApiObject(
            scope,
            "secret-rke2lab-ca-key-pair",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Secret")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(CA_SECRET_NAME)
                        .namespace(CA_SECRET_NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|Secret|" + CA_SECRET_NAMESPACE + "|" + CA_SECRET_NAME,
                                Map.of(ManifestAnnotation.NODE_BOOTSTRAP.key(), "true")))
                        .build())
                .build());
    secret.addJsonPatch(JsonPatch.add("/type", "kubernetes.io/tls"));
    secret.addJsonPatch(
        JsonPatch.add(
            "/data",
            Map.of(
                "tls.crt", base64(material.caCertChainPem()),
                "tls.key", base64(material.caKeyPem()))));
  }

  private static String base64(final String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }
}
