// @codebase
package io.seedmatic.rke2lab.manifests.units.tailscale;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.FloxAnnotation;
import io.seedmatic.rke2lab.manifests.contract.ManifestAnnotation;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.contract.ManifestLayer;
import io.seedmatic.rke2lab.manifests.ingress.FunnelLeaf;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import io.seedmatic.rke2lab.manifests.units.cluster.ClusterRefs;
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
 *       Tailscale OAuth client, filled by the replicator from the same {@code rke2lab-secrets
 *       /operator-oauth} source the operator's {@code operator-oauth} rides. A dedicated target
 *       (not a shared reference) keeps this unit self-contained — no dependency cycle with the
 *       operator unit that would otherwise render the shared secret;
 *   <li>{@link #purgeJob} — runs ndh's {@code manage-tailnet --prune-stale-devices} in a 90s guard
 *       loop, parsing its {@code --format=json} JSON Lines to know what it removed.
 * </ol>
 *
 * <p>The tailscale operator {@code dependsOn} this unit, so Flux waits for the Job to COMPLETE
 * before the operator provisions any proxy — one gate covers the funnel proxies, the controlplane
 * connector, and the operator's own device.
 */
public final class TailnetPurgeManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.TAILSCALE + "/tailnet-purge";

  private static final String NAMESPACE = TailscaleRefs.SYSTEM_NAMESPACE.name();
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
      new PackageMetadataProfile("tailscale", "tailnet-purge");

  public TailnetPurgeManifestsUnit() {
    // dependsOn funnel-cert-restore: the purge mounts that unit's persist PVC (to know which
    // funnels
    // HAVE persisted state → spare only those), and it must run AFTER the restore has
    // migrated/seeded
    // that state. The tailscale operator in turn dependsOn BOTH, so both gates precede any proxy.
    super(
        MANIFEST_UNIT_ID,
        List.of(
            TailscaleSystemNamespaceManifestsUnit.MANIFEST_UNIT_ID,
            FunnelCertRestoreManifestsUnit.MANIFEST_UNIT_ID));
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
   * client_secret) from the shared {@code rke2lab-secrets/operator-oauth} source. Only the
   * client_secret is mounted below; ndh's manage-tailnet reads it RAW as the OAuth client_secret
   * (Tailscale accepts the {@code tskey-client-…} alone). Operators layer so it exists before the
   * Job runs; no RBAC — a secret is mounted, not read through the API.
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
                                    ClusterRefs.SECRETS_NAMESPACE + "/operator-oauth",
                                    ManifestAnnotation.MANIFEST_LAYER.key(),
                                    ManifestLayer.OPERATORS.value())))
                        .build())
                .build());
    // Empty stub — NO stringData: mittwald's replicate-from fills client_id/client_secret from the
    // source. A rendered empty stringData makes Flux (SSA, force-apply) reset those keys to "" on
    // every reconcile, clobbering the replicated values — the race that left this OAuth empty
    // (401).
    secret.addJsonPatch(JsonPatch.add("/type", "Opaque"));
  }

  /**
   * The funnel leaves, space-separated — the script iterates them and passes {@code --keep-host
   * <leaf>} ONLY for a leaf whose {@code /persist/<leaf>/state.yaml} exists (there is state to
   * re-attach to). A leaf with no backup yet is NOT spared → its stale device is pruned so the
   * fresh proxy claims a clean name; the first backup then establishes its state and the next grow
   * re-attaches. Conditioning on the PV state avoids the transition footgun where sparing a device
   * we cannot re-attach to (no restore) leaves it holding the name → the new proxy drifts to {@code
   * -N}.
   */
  private String funnelLeaves() {
    final StringBuilder leaves = new StringBuilder();
    for (final FunnelLeaf funnel : FunnelLeaf.values()) {
      if (leaves.length() > 0) {
        leaves.append(' ');
      }
      leaves.append(funnel.leaf());
    }
    return leaves.toString();
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
   * keepalive window). FAIL-LOUD on an auth/API error: an empty client-secret (a stale replicated
   * OAuth) or a non-2xx from {@code manage-tailnet} FAILS the Job — never a false "clean; safe to
   * deploy" (that false negative once hid an un-pruned tailnet across grows). Runs once at bring-up
   * (a completed Job is not re-run by Flux). Spares the persisted funnels via {@link
   * #keepHostArgs}.
   */
  private void purgeJob(final Construct scope) {
    final String script =
        """
        set -uo pipefail
        guard=90
        deadline=$(( $(date +%s) + guard ))
        # FAIL LOUD, never a false "clean": the OAuth is a replicated secret, and a stale/empty
        # replica (the source populated after this target replicated) yields a 401 that must NOT be
        # mistaken for "nothing to prune" — that false negative hid an un-pruned tailnet for grows.
        if [ ! -s /etc/tailnet/client-secret ]; then
          echo "OAuth client-secret is EMPTY — the replicated tailnet-purge-oauth was not populated" >&2
          echo "(delete it so the replicator re-syncs from rke2lab-secrets/operator-oauth)" >&2
          exit 1
        fi
        # Spare ONLY funnels that HAVE persisted state on the PV — those will re-attach (same device,
        # cert reused), so deleting them breaks the reuse. A funnel with no backup yet is left to the
        # prune so its stale device is reclaimed and the fresh proxy gets a clean name.
        keep=""
        for leaf in @LEAVES@; do
          if [ -s "/persist/$leaf/state.yaml" ]; then
            keep="$keep --keep-host $leaf"
            echo "sparing $leaf — has persisted state, will re-attach"
          else
            echo "not sparing $leaf — no persisted state yet, its stale device will be pruned"
          fi
        done
        echo "tailnet stale-device prune — stop when clean, ${guard}s guard cap"
        while true; do
          # Capture output + exit code SEPARATELY (no '|| true'): an auth/API error fails the Job.
          # --keep-host spares the PERSISTED funnel devices (their identity is restored across the
          # cold-start, so they must survive to re-attach — same name, cert reused); only drifted
          # duplicates (name-1, …) and un-persisted orphans (the controlplane Connector) are pruned.
          if ! out="$(manage-tailnet --prune-stale-devices --stale-after 1s --yes $keep \
              --client-secret-file /etc/tailnet/client-secret --format=json)"; then
            echo "manage-tailnet failed (auth/API error — e.g. 401) — refusing to report clean" >&2
            printf '%s\\n' "$out" >&2
            exit 1
          fi
          pruned="$(printf '%s\\n' "$out" | yq -p=json 'select(.event == "pruned") | .host')"
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
        """
            .replace("@LEAVES@", funnelLeaves());
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
                // needs a manual `kubectl delete job`). A genuinely-broken env still fails
                // eventually.
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
                              new Object[] {"bash", "-c", script},
                              "volumeMounts",
                              new Object[] {
                                Map.of(
                                    "name",
                                    "oauth",
                                    "mountPath",
                                    OAUTH_MOUNT,
                                    "readOnly",
                                    Boolean.TRUE),
                                // The persist PV (owned by FunnelCertRestore) — read-only, to learn
                                // which funnels have state (→ --keep-host them, they re-attach).
                                Map.of(
                                    "name",
                                    "persist",
                                    "mountPath",
                                    "/persist",
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
                                  })),
                          Map.of(
                              "name",
                              "persist",
                              "persistentVolumeClaim",
                              Map.of("claimName", FunnelCertRestoreManifestsUnit.PV_NAME))
                        })))));
  }
}
