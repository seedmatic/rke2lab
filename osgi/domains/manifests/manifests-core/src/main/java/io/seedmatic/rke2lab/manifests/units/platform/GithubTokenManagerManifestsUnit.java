package io.seedmatic.rke2lab.manifests.units.platform;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestAnnotation;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.contract.ManifestLayer;
import io.seedmatic.rke2lab.manifests.contract.profiles.GithubAppMaterial;
import io.seedmatic.rke2lab.manifests.ingress.Component;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import io.seedmatic.rke2lab.manifests.units.cluster.ClusterRefs;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

/**
 * Deploys isometry/github-token-manager (gtm) — the k8s operator that MINTS + refreshes GitHub App
 * installation tokens into Secrets — plus the {@code App}/{@code ClusterToken} CRs that mint the
 * {@code github-token} Secret the flox-controller consumes.
 *
 * <p><b>Why.</b> The flox-controller realises FloxEnvs by running {@code flox activate} on the node
 * (via nsenter); resolving a FloxEnv flake re-fetches its inputs — including the PRIVATE {@code
 * github:seedmatic/claude-hub} (via ndh) — and the node's nix has NO {@code access-tokens}, so the
 * anonymous fetch 404s and the mesh/cicd envs never realise. gtm mints a short-lived App token into
 * {@code github-token}; the flox-controller reads it and passes {@code access-tokens =
 * github.com=<token>} as {@code NIX_CONFIG} to that nix. The controller does NOT mint (not its
 * role) — gtm owns the mint+refresh, as the memory {@code flox-controller-github-token-via-gtm}
 * decided.
 *
 * <p><b>Shape.</b> Like {@link CertManagerManifestsUnit}, the chart rides an RKE2 {@code
 * helm.cattle.io/v1 HelmChart} (kube-system → {@code targetNamespace} rke2lab-system) pinned via
 * {@link Component#GITHUB_TOKEN_MANAGER}; {@code crds.install} is the chart default, so it
 * registers the {@code github.as-code.io} group at runtime (declared in {@code
 * FluxServiceKustomizationPlanner}'s runtime installers, so the {@code App}/{@code ClusterToken}
 * cells order after it). The chart is on the OPERATORS layer; the App key Secret + CRs stay on the
 * default WORKLOADS layer (after operators).
 *
 * <p>The App private key rides the BRANCH sops-encrypted (a plain Secret → the exploder names it
 * {@code *-secret-*.yml} → the {@code sops-yaml} clean filter encrypts its {@code data}; Flux
 * decrypts on reconcile), NOT the flux-system bootstrap lane the {@code githubapp} Secret uses —
 * gtm is a workload that comes up after Flux, not a Flux-bootstrap prerequisite. Absent App
 * material (a bare survey / before the ghapp registration filed) ⇒ nothing rendered.
 */
public final class GithubTokenManagerManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID =
      ManifestDomainCatalog.PLATFORM + "/github-token-manager";

  /** The minted-token Secret the flox-controller reads (rke2lab-system) + its data key. */
  public static final String TOKEN_SECRET_NAME = "github-token";

  public static final String TOKEN_SECRET_KEY = "token";

  private static final String APP_KEY_SECRET = "github-app-private-key";
  private static final String APP_KEY_DATA = "private-key.pem";
  private static final String APP_NAME = "github-app";

  // gtm's CRD group + version, and the OCI chart. The chart is versioned separately from the app
  // (the source Chart.yaml reads 0.4.0; the PUBLISHED OCI chart is aligned to the app, 1.6.1) — so
  // the pin lives in Component.GITHUB_TOKEN_MANAGER as a chart version with no GitHub bump source.
  private static final String CRD_API_VERSION = "github.as-code.io/v1";
  private static final String CHART_OCI = "oci://ghcr.io/isometry/charts/github-token-manager";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile(
          "platform", "github-token-manager", false, ManifestLayer.WORKLOADS);

  public GithubTokenManagerManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    final Optional<GithubAppMaterial> maybeApp = ManifestSynthesisContext.current().githubApp();
    if (maybeApp.isEmpty()) {
      return;
    }
    final GithubAppMaterial app = maybeApp.orElseThrow();
    final String namespace = ClusterRefs.RUNTIME_SYSTEM_NAMESPACE.name();

    createHelmChart(scope);
    final ApiObject key = createAppKeySecret(scope, namespace, app);
    final ApiObject appCr = createApp(scope, namespace, app, key);
    createClusterToken(scope, namespace, appCr);
  }

  /** The RKE2 HelmChart for gtm — kube-system, installs into rke2lab-system, OPERATORS layer. */
  private void createHelmChart(final Construct scope) {
    final String version =
        ManifestSynthesisContext.current().componentVersions().of(Component.GITHUB_TOKEN_MANAGER);
    final ApiObject helmChart =
        new ApiObject(
            scope,
            "helmchart-github-token-manager",
            ApiObjectProps.builder()
                .apiVersion("helm.cattle.io/v1")
                .kind("HelmChart")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("github-token-manager")
                        .namespace("kube-system")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "helm.cattle.io|HelmChart|kube-system|github-token-manager",
                                Map.of(
                                    ManifestAnnotation.MANIFEST_LAYER.key(),
                                    ManifestLayer.OPERATORS.value())))
                        .build())
                .build());
    // OCI chart: `chart` carries the full oci:// URL, NO `repo`. crds.install is the chart default,
    // so github.as-code.io is registered by the install (see the planner's runtime installers).
    helmChart.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "chart", CHART_OCI,
                "version", version,
                "targetNamespace", ClusterRefs.RUNTIME_SYSTEM_NAMESPACE.name())));
  }

  /** The App private key Secret (branch + sops-encrypted, data key {@code private-key.pem}). */
  private ApiObject createAppKeySecret(
      final Construct scope, final String namespace, final GithubAppMaterial app) {
    final ApiObject secret =
        new ApiObject(
            scope,
            "secret-github-app-private-key",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Secret")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(APP_KEY_SECRET)
                        .namespace(namespace)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "|Secret|" + namespace + "|" + APP_KEY_SECRET))
                        .build())
                .build());
    secret.addJsonPatch(JsonPatch.add("/type", "Opaque"));
    secret.addJsonPatch(
        JsonPatch.add(
            "/data",
            Map.of(
                APP_KEY_DATA,
                Base64.getEncoder()
                    .encodeToString(app.privateKeyPem().getBytes(StandardCharsets.UTF_8)))));
    return secret;
  }

  /** The gtm {@code App} CR — the App identity + a secret-provider key ref. */
  private ApiObject createApp(
      final Construct scope,
      final String namespace,
      final GithubAppMaterial app,
      final ApiObject key) {
    final ApiObject appCr =
        new ApiObject(
            scope,
            "githubapp-github-app",
            ApiObjectProps.builder()
                .apiVersion(CRD_API_VERSION)
                .kind("App")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(APP_NAME)
                        .namespace(namespace)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "github.as-code.io|App|" + namespace + "|" + APP_NAME))
                        .build())
                .build());
    // appID / installationID are INTs in the CRD (GithubAppMaterial carries them as strings).
    appCr.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "appID", Integer.parseInt(app.appId()),
                "installationID", Integer.parseInt(app.installationId()),
                "provider", "secret",
                "keyRef", Map.of("name", APP_KEY_SECRET))));
    // The key Secret must land before the App CR reads it.
    appCr.addDependency(key);
    return appCr;
  }

  /**
   * The cluster-scoped {@code ClusterToken} CR — mints + refreshes the {@code github-token} Secret
   * with a {@code contents:read}+{@code metadata:read} installation token (enough for nix to fetch
   * the private flake inputs). refreshInterval < the App token's ~1h TTL.
   */
  private void createClusterToken(
      final Construct scope, final String namespace, final ApiObject appCr) {
    final ApiObject token =
        new ApiObject(
            scope,
            "githubclustertoken-github-token",
            ApiObjectProps.builder()
                .apiVersion(CRD_API_VERSION)
                .kind("ClusterToken")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(TOKEN_SECRET_NAME)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "github.as-code.io|ClusterToken||" + TOKEN_SECRET_NAME))
                        .build())
                .build());
    token.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "appRef", Map.of("name", APP_NAME, "namespace", namespace),
                "permissions", Map.of("metadata", "read", "contents", "read"),
                "refreshInterval", "45m",
                "secret", Map.of("name", TOKEN_SECRET_NAME, "namespace", namespace))));
    token.addDependency(appCr);
  }
}
