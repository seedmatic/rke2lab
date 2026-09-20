package io.seedmatic.rke2lab.manifests.units.gitops;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

/**
 * The chicken-and-egg solver for the rendered-branch GitOps model: the bootstrap objects — a {@code
 * GitRepository} on THIS cluster's rendered branch plus a layered {@code Kustomization} stack —
 * that, once seeded from the node's local-only server-manifests, let Flux self-manage everything
 * else from the branch (see {@code docs/architecture/cluster-api/manifests-rendered-branches.adoc}
 * §layers and {@code github-credential-model.adoc}).
 *
 * <p><b>GitRepository:</b> {@code https://github.com/seedmatic/rke2lab.git} (HTTPS — App auth, not
 * SSH), tracking {@code ref.branch: manifests/<host>-<role>} (this cluster's rendered branch, the
 * EXACT branch the manifests scion pushes — {@link
 * io.seedmatic.rke2lab.manifests.contract.profiles.BootstrapIdentity#clusterSlug()}). It
 * authenticates as the one org-owned GitHub App via {@code spec.provider: github} + {@code
 * spec.secretRef} → the {@code githubapp} Secret ({@code githubAppID}, {@code
 * githubAppInstallationID}, {@code githubAppPrivateKey}); Flux self-mints and self-refreshes a
 * {@code contents:read} pull token. The {@code provider: github} is mandatory — source-controller
 * rejects an App-data secret without it.
 *
 * <p><b>Root Kustomization:</b> a single {@code Kustomization} over {@code ./flux} — the in-branch
 * dir the {@link io.seedmatic.rke2lab.manifests.FluxServiceKustomizationPlanner} fills post-explode
 * with a three-level tree: an explicit {@code kustomization.yaml} listing the per-DOMAIN
 * Kustomizations, each of which ({@code ./flux/<domain>}) owns its per-SERVICE Kustomizations. The
 * root applies the domain Kustomizations; each service reconciles its own {@code
 * ./<layer>/<domain>/<package>} path and carries the DERIVED dependsOn graph (CRD→CR provider edges
 * + service→service edges — no global layer barrier) that orders the reconcile, so an operator sees
 * per-service progress in {@code flux get kustomizations} (grouped by domain in {@code flux tree}),
 * and a CR dry-runs only once its CRD exists. {@code wait: true} makes the root Ready only once
 * every domain — and so every service — is. The two Secrets (App-auth + age) ride the same
 * local-only bootstrap lane so Flux comes up able to pull + decrypt.
 */
public final class FluxRootManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.GITOPS + "/flux-root";

  private static final String REPO_URL = "https://github.com/seedmatic/rke2lab.git";
  private static final String BRANCH_PREFIX = "manifests/";
  public static final String GIT_REPOSITORY_NAME = "rke2lab";
  private static final String APP_AUTH_SECRET = "githubapp";
  public static final String SOPS_AGE_SECRET = "sops-age";

  /** The in-branch dir the root Kustomization reconciles; filled with per-service children. */
  public static final String FLUX_DIR = "flux";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("gitops", "flux-root", true);

  public FluxRootManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of(FluxInstanceManifestsUnit.MANIFEST_UNIT_ID));
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    final String clusterSlug = ManifestSynthesisContext.current().bootstrapIdentity().clusterSlug();
    createGitRepository(scope, clusterSlug);
    createRootKustomization(scope);
  }

  private void createGitRepository(Construct scope, String clusterSlug) {
    final ApiObject gitRepo =
        new ApiObject(
            scope,
            "gitrepository-rke2lab",
            ApiObjectProps.builder()
                .apiVersion("source.toolkit.fluxcd.io/v1")
                .kind("GitRepository")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(GIT_REPOSITORY_NAME)
                        .namespace("flux-system")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "source.toolkit.fluxcd.io|GitRepository|flux-system|"
                                    + GIT_REPOSITORY_NAME))
                        .build())
                .build());

    gitRepo.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                // The Receiver is the trigger now, not this interval — a signed GitHub push
                // reconciles this source at once (FluxReceiverManifestsUnit), so polling every
                // minute only hammers GitHub for latency the webhook already removed.
                //
                // Deliberately 10m and not 1h: BOOTSTRAP still rides this interval.  The webhook
                // needs its flux-webhook-token replica (filled by mittwald) and a reachable
                // receiver path, none of which exists at cold start — so the worst case here is
                // how long an unattended bootstrap can stall before the first fetch.  10m trades
                // the hammering away while keeping that bounded.
                //
                // The child Kustomizations keep their own 5m: they react to a source revision
                // change immediately, so their interval is drift correction, not latency.
                "interval",
                "10m",
                "url",
                REPO_URL,
                "ref",
                Map.of("branch", BRANCH_PREFIX + clusterSlug),
                // GitHub App auth REQUIRES provider=github; without it source-controller rejects
                // the
                // App-data secret ("has github app data but provider is not set to github").
                "provider",
                "github",
                "secretRef",
                Map.of("name", APP_AUTH_SECRET))));
  }

  /**
   * The single ROOT {@code Kustomization} over {@code ./flux} — the in-branch dir the {@link
   * io.seedmatic.rke2lab.manifests.FluxServiceKustomizationPlanner} fills post-explode with one
   * child Kustomization per service {@code (layer, domain, package)} cell. The root applies those
   * children (pruned + waited); each child reconciles its own path and carries the dependsOn graph.
   * No {@code decryption} here — the root applies only child Kustomization objects (no Secrets);
   * the children carry their own sops decryption for the resources they apply.
   */
  private void createRootKustomization(Construct scope) {
    // Un-prefixed: each cluster has its own rendered branch + flux-system namespace, so the
    // cluster slug in the name is pure redundancy — the children are bare <domain>-<package> too.
    final String name = "flux";
    final ApiObject kustomization =
        new ApiObject(
            scope,
            "kustomization-flux-root",
            ApiObjectProps.builder()
                .apiVersion("kustomize.toolkit.fluxcd.io/v1")
                .kind("Kustomization")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(name)
                        .namespace("flux-system")
                        .annotations(
                            packageProfile.packageAnnotations(
                                "kustomize.toolkit.fluxcd.io|Kustomization|flux-system|" + name))
                        .build())
                .build());

    final LinkedHashMap<String, Object> spec = new LinkedHashMap<>();
    spec.put("interval", "5m");
    spec.put("path", "./" + FLUX_DIR);
    spec.put("prune", true);
    spec.put("wait", true);
    spec.put("sourceRef", Map.of("kind", "GitRepository", "name", GIT_REPOSITORY_NAME));
    kustomization.addJsonPatch(JsonPatch.add("/spec", spec));
  }
}
