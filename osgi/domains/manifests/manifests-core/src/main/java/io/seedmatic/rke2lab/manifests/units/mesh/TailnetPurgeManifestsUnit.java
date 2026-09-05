// @codebase
package io.seedmatic.rke2lab.manifests.units.mesh;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.FloxAnnotation;
import io.seedmatic.rke2lab.manifests.contract.ManifestAnnotation;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.contract.ManifestLayer;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

/**
 * The in-cluster tailnet stale-device GC — the gate that clears the tailnet BEFORE the tailscale
 * operator (hence any proxy) registers, so the funnel proxies never drift to a {@code -1} MagicDNS
 * suffix behind a stale device from a prior cluster (the GitHub-App webhook points at a stable
 * {@code pipelines-webhook} FQDN — a drift breaks the in-cluster render). See {@code
 * docs/architecture/cluster-api/pac-in-cluster-render-spec.adoc}.
 *
 * <p>Renders (operators layer, so it lands before the operator):
 *
 * <ol>
 *   <li>{@link #serviceAccount} for the Job;
 *   <li>{@link #oauthSecret} — its OWN replicated copy ({@code tailnet-purge-oauth}) of the
 *       Tailscale OAuth client, filled by the replicator from the same {@code
 *       rke2lab-replicator-source /operator-oauth} source the operator's {@code operator-oauth}
 *       rides. A dedicated target (not a shared reference) keeps this unit self-contained — no
 *       dependency cycle with the operator unit that would otherwise render the shared secret;
 *   <li>{@link #purgeJob} — runs ndh's {@code manage-tailnet --prune-stale-devices} in a 90s guard
 *       loop, parsing its {@code --format=json} JSON Lines to know what it removed.
 * </ol>
 *
 * <p>The tailscale operator {@code dependsOn} this unit, so Flux waits for the Job to COMPLETE
 * before the operator provisions any proxy — one gate covers the funnel proxies, the controlplane
 * connector, and the operator's own device.
 */
public final class TailnetPurgeManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.MESH + "/tailnet-purge";

  private static final String NAMESPACE = MeshRefs.MESH_SYSTEM_NAMESPACE.name();
  private static final String SERVICE_ACCOUNT = "tailnet-purge";
  private static final String PURGE_CONTAINER = "purge";

  /**
   * The flox env (folder/name) that puts manage-tailnet + yq-go on PATH — see FloxEnvManifestsUnit.
   */
  private static final String PURGE_ENV = "mesh/tailnet";

  /**
   * This unit's own replicated Tailscale-OAuth target; the client_secret is mounted for the API.
   */
  private static final String OAUTH_SECRET = "tailnet-purge-oauth";

  private static final String OAUTH_MOUNT = "/etc/tailnet";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("mesh", "tailnet-purge");

  public TailnetPurgeManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of(MeshSystemNamespaceManifestsUnit.MANIFEST_UNIT_ID));
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    serviceAccount(scope);
    oauthSecret(scope);
    purgeJob(scope);
  }

  private void serviceAccount(final Construct scope) {
    new ApiObject(
        scope,
        "sa-tailnet-purge",
        ApiObjectProps.builder()
            .apiVersion("v1")
            .kind("ServiceAccount")
            .metadata(
                ApiObjectMetadata.builder()
                    .name(SERVICE_ACCOUNT)
                    .namespace(NAMESPACE)
                    .annotations(
                        packageProfile.packageAnnotations(
                            "",
                            Map.of(
                                ManifestAnnotation.MANIFEST_LAYER.key(),
                                ManifestLayer.OPERATORS.value())))
                    .build())
            .build());
  }

  /**
   * The purge Job's own replicated Tailscale-OAuth secret — the replicator fills it (client_id +
   * client_secret) from the shared {@code rke2lab-replicator-source/operator-oauth} source. Only
   * the client_secret is mounted below; ndh's manage-tailnet reads it RAW as the OAuth
   * client_secret (Tailscale accepts the {@code tskey-client-…} alone). Operators layer so it
   * exists before the Job runs; no RBAC — a secret is mounted, not read through the API.
   */
  private void oauthSecret(final Construct scope) {
    final ApiObject secret =
        new ApiObject(
            scope,
            "secret-tailnet-purge-oauth",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Secret")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(OAUTH_SECRET)
                        .namespace(NAMESPACE)
                        .labels(Map.of("app.kubernetes.io/replicated", "true"))
                        .annotations(
                            packageProfile.packageAnnotations(
                                "",
                                Map.of(
                                    "replicator.v1.mittwald.de/replicate-from",
                                    "rke2lab-replicator-source/operator-oauth",
                                    ManifestAnnotation.MANIFEST_LAYER.key(),
                                    ManifestLayer.OPERATORS.value())))
                        .build())
                .build());
    secret.addJsonPatch(
        JsonPatch.add("/type", "Opaque"),
        JsonPatch.add("/stringData", Map.of("client_id", "", "client_secret", "")));
  }

  /**
   * Prune stale tailnet devices, retrying until a pass finds NOTHING left to remove — then STOP
   * (the colliding devices are gone → safe to deploy). A 90s guard CAPS the loop: if devices keep
   * appearing past 90s the Job proceeds anyway (fail-open — a lingering device is a nuisance,
   * blocking the whole cluster is worse). Aggressive {@code --stale-after 1s}, SAFE here because
   * this runs BEFORE any new device registers, so only prior-cluster devices can match. On a cold
   * start those have been offline for MINUTES by the time this runs (image build + boot + Flux) →
   * pass 1 prunes them, pass 2 is clean → the loop exits in seconds; the 90s cap only matters for a
   * pathological fast re-grow (tailscale marks a just-deleted node offline only after its ~50s
   * keepalive window). Best-effort: a prune error is non-fatal ({@code || true}, no {@code set -e}
   * on the pipe). Runs once at bring-up (a completed Job is not re-run by Flux).
   */
  private void purgeJob(final Construct scope) {
    final String script =
        """
        set -uo pipefail
        guard=90
        deadline=$(( $(date +%s) + guard ))
        echo "tailnet stale-device prune — stop when clean, ${guard}s guard cap"
        while true; do
          pruned="$(manage-tailnet --prune-stale-devices --stale-after 1s --yes \
            --client-secret-file /etc/tailnet/client-secret --format=json \
            | yq -p=json 'select(.event == "pruned") | .host' || true)"
          if [ -z "$pruned" ]; then
            echo "no stale devices left to prune — tailnet clean; safe to deploy"
            exit 0
          fi
          echo "pruned: $(echo "$pruned" | tr '\\n' ' ')"
          if [ "$(date +%s)" -ge "$deadline" ]; then
            echo "guard window elapsed while still pruning — proceeding anyway (safe)"
            exit 0
          fi
          sleep 5
        done
        """;
    final String floxImage = ManifestSynthesisContext.current().floxDebugPolicy().prodImage();
    final ApiObject jobObject =
        new ApiObject(
            scope,
            "job-tailnet-purge",
            ApiObjectProps.builder()
                .apiVersion("batch/v1")
                .kind("Job")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("tailnet-purge")
                        .namespace(NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "",
                                Map.of(
                                    ManifestAnnotation.MANIFEST_LAYER.key(),
                                    ManifestLayer.OPERATORS.value())))
                        .build())
                .build());
    jobObject.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                // Generous: at cold-start the flox-controller realises FloxEnvs serially (~1/min),
                // so this Job's flox-wait init may time out several times before the mesh/tailnet
                // GC-root exists. A high backoffLimit lets the pod keep retrying until the env is
                // realised (rather than failing the Job and wedging the operator gate — which then
                // needs a manual `kubectl delete job`). A genuinely-broken env still fails eventually.
                "backoffLimit",
                20,
                "template",
                Map.of(
                    "metadata",
                    Map.of(
                        "annotations",
                        Map.of(
                            FloxAnnotation.ENVIRONMENT.forContainer(PURGE_CONTAINER), PURGE_ENV)),
                    "spec",
                    Map.of(
                        "serviceAccountName",
                        SERVICE_ACCOUNT,
                        "restartPolicy",
                        "OnFailure",
                        "containers",
                        new Object[] {
                          Map.of(
                              "name",
                              PURGE_CONTAINER,
                              "image",
                              floxImage,
                              "command",
                              new Object[] {
                                "flox", "activate", "--dir", "/root", "--", "bash", "-c", script
                              },
                              "volumeMounts",
                              new Object[] {
                                Map.of(
                                    "name",
                                    "oauth",
                                    "mountPath",
                                    OAUTH_MOUNT,
                                    "readOnly",
                                    Boolean.TRUE)
                              })
                        },
                        "volumes",
                        new Object[] {
                          Map.of(
                              "name",
                              "oauth",
                              "secret",
                              Map.of(
                                  "secretName",
                                  OAUTH_SECRET,
                                  "items",
                                  new Object[] {
                                    Map.of("key", "client_secret", "path", "client-secret")
                                  }))
                        })))));
  }
}
