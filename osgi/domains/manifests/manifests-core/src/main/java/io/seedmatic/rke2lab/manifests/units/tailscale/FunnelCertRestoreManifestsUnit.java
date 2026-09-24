// @codebase
package io.seedmatic.rke2lab.manifests.units.tailscale;

import io.seedmatic.rke2lab.dataplan.contract.DataplanLayout;
import io.seedmatic.rke2lab.dataplan.contract.DataplanLayout.ClusterDataplan;
import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.FloxAnnotation;
import io.seedmatic.rke2lab.manifests.contract.ManifestAnnotation;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.contract.ManifestLayer;
import io.seedmatic.rke2lab.manifests.ingress.Funnel;
import io.seedmatic.rke2lab.manifests.ingress.FunnelCertIssuance;
import io.seedmatic.rke2lab.manifests.ingress.FunnelLeaf;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import io.seedmatic.rke2lab.manifests.units.runtime.SeedInclusterManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.storage.OpenebsZfsManifestsUnit;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

/**
 * The funnel-cert RESTORE half — the ordering keystone of the durable-funnel fix (see {@link
 * FunnelStatePersistenceManifestsUnit} for the backup half and {@code
 * docs/architecture/cluster-api/pac-in-cluster-render-spec.adoc} § funnel-durability / § the
 * ordering is STRUCTURAL). It declares this cluster's persist volume — a {@code VolumeIntention} on
 * {@code tank/rke2lab/<role>/persist/funnel-cert} plus the PVC that claims it — and renders the
 * restore Job that seeds the saved tailscale funnel state (node key + cert) into the stable-named
 * state Secret. The {@code ZFSVolume} + static PV the intention resolves to are created by the
 * in-cluster volume controller, which is the only party that can know a managed node's name.
 *
 * <p><b>Why a SEPARATE unit, before the operator.</b> The invariant is that the tailscale proxy,
 * when it starts, finds the persisted cert ALREADY in its state Secret — so it reuses it (zero
 * ACME). The operator is the single actor that creates the proxy (and the Secret, if absent) when
 * it reconciles the funnel Ingress, so the restore must land the Secret BEFORE the operator runs.
 * This unit renders NO {@code tailscale.com} CR, so it depends only on openebs (the derived {@code
 * zfs.openebs.io} CRD edge) — NOT on the tailscale operator; instead {@link TailscaleManifestsUnit}
 * {@code dependsOn} THIS unit, so Flux health-gates the restore Job to completion before the
 * operator is up to provision any proxy (which then adopts the pre-seeded Secret via its {@code
 * sts.go} {@code Get}+{@code MergeFrom}). Splitting the restore out of {@link
 * FunnelStatePersistenceManifestsUnit} breaks the cycle a single combined unit would form (operator
 * → restore, and the unit's {@code ProxyClass} → operator). It also serialises the two Jobs —
 * restore completes far up-chain, the backup runs far down-chain — so they never contend for the
 * one RWO persist PVC (no shared-mount storage class needed).
 */
public final class FunnelCertRestoreManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID =
      ManifestDomainCatalog.TAILSCALE + "/funnel-cert-restore";

  private static final String NAMESPACE = TailscaleRefs.SYSTEM_NAMESPACE.name();

  private static final String STORAGE_CLASS = OpenebsZfsManifestsUnit.PERSIST_CLASS;

  /**
   * The persist PVC name the restore Jobs and the backup Jobs (funnel-state) both mount — ONE
   * volume shared by every funnel, each under its own {@code /persist/<leaf>/} subdir. The name is
   * the dataplan's {@code funnel-cert} dataset (SSOT) so the openebs volumeHandle ADOPTS the
   * ndh-pre-created dataset instead of dynamically creating a divergent {@code
   * pipelines-webhook-funnel-cert} one (the orphan that left two datasets).
   */
  public static final String PV_NAME = DataplanLayout.FUNNEL_CERT;

  // A cert-state Secret mirror (tailscale node key + cert) is a few KB; 16Mi is generous headroom.
  private static final String CAPACITY = "16Mi";

  private static final String MIRROR_ENV = "toolchains/kube";
  private static final String MIRROR_CONTAINER = "mirror";
  private static final String SERVICE_ACCOUNT = "funnel-cert-restore";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("tailscale", "funnel-cert-restore");

  public FunnelCertRestoreManifestsUnit() {
    // tailscale-system must exist for the Job/PVC/SA (explicit, as TailscaleManifestsUnit declares
    // it); seed-incluster must be UP, because it is what turns this unit's VolumeIntention into the
    // ZFSVolume + PV the PVC binds against — without that edge Flux would health-gate a PVC nobody
    // can satisfy yet. The openebs edge is DERIVED by the planner (zfs.openebs.io CR → installer).
    // This unit MUST NOT depend on the tailscale operator — the operator dependsOn IT, so the
    // Secret
    // is seeded before any proxy is provisioned.
    super(
        MANIFEST_UNIT_ID,
        List.of(
            TailscaleSystemNamespaceManifestsUnit.MANIFEST_UNIT_ID,
            SeedInclusterManifestsUnit.MANIFEST_UNIT_ID));
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    final String cluster = context.nodeEnvContext().bootstrapIdentity().clusterName();
    volumeIntention(scope, cluster);
    persistentVolumeClaim(scope);
    serviceAccount(scope);
    role(scope);
    roleBinding(scope);
    for (final FunnelLeaf leaf : FunnelLeaf.values()) {
      restoreJob(scope, Funnel.of(cluster, leaf));
    }
  }

  /**
   * The {@code VolumeIntention} — this cluster's INTENT for its persist volume: the dataset to
   * adopt, the size, the claim to satisfy, and the kind of node eligible to serve it. It names NO
   * node, because a managed node's name is not render-time knowledge: on a CAPI-provisioned cluster
   * the name is random ({@code …-control-plane-v9fhz}), so a literal can never match. The former
   * literal here ({@code bioskop-mgmt-master}) was not a value to parameterise but the admission
   * that this unit had only ever been meant to run on a host-grown cluster.
   *
   * <p>The {@code ZFSVolume} + the static PV that were rendered here are now created by the
   * in-cluster volume controller, which elects a node and stamps both {@code ownerNodeID} (which
   * MUST equal the {@code ZFSNode} object's name — a node LABEL cannot serve that half) and the
   * PV's {@code nodeAffinity}. One owner, at runtime, where the fact lives. The static-adopt shape
   * itself is unchanged and still load-bearing: a cold start wipes the PVC object, so dynamic
   * provisioning would mint a fresh {@code pvc-<uuid>}, leak the old dataset and lose the cert — a
   * pre-declared volume adopting a stable dataset name is the only handle that survives an etcd
   * wipe.
   *
   * <p>See {@code docs/architecture/cluster-api/pac-in-cluster-render-spec.adoc} §
   * funnel-node-affinity.
   */
  private void volumeIntention(final Construct scope, final String cluster) {
    final ApiObject intention =
        new ApiObject(
            scope,
            "volumeintention-funnel-cert",
            ApiObjectProps.builder()
                .apiVersion("cluster.seedmatic.io/v1alpha1")
                .kind("VolumeIntention")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(PV_NAME)
                        .namespace(NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "",
                                Map.of(
                                    ManifestAnnotation.MANIFEST_LAYER.key(),
                                    ManifestLayer.OPERATORS.value())))
                        .build())
                .build());
    // pool + dataset come from the dataplan SSOT, per-cluster: two clusters sharing one funnel-cert
    // dataset would have both proxies overwrite one state file, both return as new tailnet devices
    // and both re-issue — the 429 this whole mechanism exists to prevent.
    intention.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "pool",
                ClusterDataplan.of(cluster).persistPool(),
                "dataset",
                DataplanLayout.FUNNEL_CERT,
                "capacity",
                CAPACITY,
                "storageClassName",
                STORAGE_CLASS,
                "claimRef",
                Map.of("namespace", NAMESPACE, "name", PV_NAME),
                "nodeRole",
                "control-plane")));
  }

  /** The stable PVC the restore Job (here) and the backup Job (funnel-state) mount. */
  private void persistentVolumeClaim(final Construct scope) {
    final ApiObject pvc =
        new ApiObject(
            scope,
            "pvc-funnel-cert",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("PersistentVolumeClaim")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(PV_NAME)
                        .namespace(NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "",
                                Map.of(
                                    ManifestAnnotation.MANIFEST_LAYER.key(),
                                    ManifestLayer.OPERATORS.value())))
                        .build())
                .build());
    pvc.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "accessModes",
                new Object[] {"ReadWriteOnce"},
                "storageClassName",
                STORAGE_CLASS,
                "volumeName",
                PV_NAME,
                "resources",
                Map.of("requests", Map.of("storage", CAPACITY)))));
  }

  private void serviceAccount(final Construct scope) {
    new ApiObject(
        scope,
        "sa-funnel-cert-restore",
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
   * Namespaced Role: apply the state Secret (get/create/update/patch — no watch; restore writes).
   */
  private void role(final Construct scope) {
    final ApiObject role =
        new ApiObject(
            scope,
            "role-funnel-cert-restore",
            ApiObjectProps.builder()
                .apiVersion("rbac.authorization.k8s.io/v1")
                .kind("Role")
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
    role.addJsonPatch(
        JsonPatch.add(
            "/rules",
            new Object[] {
              Map.of(
                  "apiGroups", new Object[] {""},
                  "resources", new Object[] {"secrets"},
                  "verbs", new Object[] {"get", "create", "update", "patch"})
            }));
  }

  private void roleBinding(final Construct scope) {
    final ApiObject binding =
        new ApiObject(
            scope,
            "rolebinding-funnel-cert-restore",
            ApiObjectProps.builder()
                .apiVersion("rbac.authorization.k8s.io/v1")
                .kind("RoleBinding")
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
    binding.addJsonPatch(
        JsonPatch.add(
            "/roleRef",
            Map.of(
                "apiGroup", "rbac.authorization.k8s.io",
                "kind", "Role",
                "name", SERVICE_ACCOUNT)),
        JsonPatch.add(
            "/subjects",
            new Object[] {
              Map.of("kind", "ServiceAccount", "name", SERVICE_ACCOUNT, "namespace", NAMESPACE)
            }));
  }

  /**
   * Restore Job (operators layer): if the PVC holds a saved state Secret, apply it into the
   * stable-named Secret so the operator's proxy adopts the prior identity + cert. Idempotent — a
   * fresh persist volume (no backup yet) is a clean first grow. The tailscale operator {@code
   * dependsOn} this unit, so Flux waits for this Job to COMPLETE before the operator provisions any
   * proxy.
   */
  private void restoreJob(final Construct scope, final Funnel funnel) {
    final String script =
        """
        set -euo pipefail
        dir=/persist/%s
        mkdir -p "$dir"
        if [ -s "$dir/state.yaml" ]; then
          echo "restoring saved tailscale funnel state into %s"
          # A persisted cert is worth seeding ONLY if it is ours and from the current issuer. Keeping a
          # mismatched one is worse than keeping none: tailscaled renews on DATES alone — it never
          # compares the issuer, and the ACME directory URL is merely logged (feature/acme:
          # shouldStartDomainRenewal is ARI-or-expiry) — so the wrong cert would be served for the rest
          # of its ~90 days instead of replaced. Dropped, tailscaled mints a fresh one on first serve.
          #
          # Two mismatches, one rule. A different Let's Encrypt posture (the staging<->production flip),
          # or a cert for another hostname (the funnel rename, whose old <fqdn>.crt would otherwise
          # linger forever). Only the cert keys go: the node key stays, so the proxy returns as the SAME
          # tailnet device and the purge has nothing to reclaim — one issuance, no identity churn.
          want=%s
          have="$(cat "$dir/issuance" 2>/dev/null || echo UNKNOWN)"
          if [ "$have" = "$want" ]; then
            keep='.data |= with_entries(select((.key | test("[.](crt|key)$") | not) or (.key | test("^%s[.]"))))'
          else
            echo "the persisted cert was issued under $have but the posture is $want — dropping it"
            keep='.data |= with_entries(select(.key | test("[.](crt|key)$") | not))'
          fi
          # Strip the embedded metadata.namespace: a backup captured under a PRIOR namespace (the
          # persist PV is Retain, so it survives a namespace rename like ingress-system ->
          # tailscale-system) would otherwise fail the apply with "namespace from the provided object
          # does not match". Namespace-agnostic — the apply -n places it correctly. The NAME is
          # rewritten too: a backup taken before the funnel identity became per-cluster carries the old
          # bare-leaf Secret name, and applying it under that name would seed a Secret no ProxyClass
          # pins.
          yq "del(.metadata.namespace) | .metadata.name = \\"%s\\" | $keep" "$dir/state.yaml" \\
            | kubectl apply -n %s -f -
        else
          echo "no saved funnel state for %s on the persist volume — clean first grow"
        fi
        """
            .formatted(
                funnel.leafName(),
                funnel.stateSecret(),
                FunnelCertIssuance.current().name(),
                funnel.hostname(),
                funnel.stateSecret(),
                NAMESPACE,
                funnel.hostname());
    final String floxImage = ManifestSynthesisContext.current().floxDebugPolicy().prodImage();
    final ApiObject jobObject =
        new ApiObject(
            scope,
            "job-funnel-restore-" + funnel.leafName(),
            ApiObjectProps.builder()
                .apiVersion("batch/v1")
                .kind("Job")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("funnel-cert-restore-" + funnel.leafName())
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
                "backoffLimit",
                3,
                "template",
                Map.of(
                    "metadata",
                    Map.of(
                        "annotations",
                        Map.of(
                            FloxAnnotation.ENVIRONMENT.forContainer(MIRROR_CONTAINER), MIRROR_ENV)),
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
                              MIRROR_CONTAINER,
                              "image",
                              floxImage,
                              "command",
                              new Object[] {"bash", "-c", script},
                              "volumeMounts",
                              new Object[] {Map.of("name", "persist", "mountPath", "/persist")})
                        },
                        "volumes",
                        new Object[] {
                          Map.of(
                              "name",
                              "persist",
                              "persistentVolumeClaim",
                              Map.of("claimName", PV_NAME))
                        })))));
  }
}
