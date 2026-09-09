// @codebase
package io.seedmatic.rke2lab.manifests.units.tailscale;

import io.seedmatic.rke2lab.dataplan.contract.DataplanLayout;
import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.FloxAnnotation;
import io.seedmatic.rke2lab.manifests.contract.ManifestAnnotation;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.contract.ManifestLayer;
import io.seedmatic.rke2lab.manifests.ingress.PacWebhookFunnel;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
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
 * ordering is STRUCTURAL). It owns the persist volume ({@code ZFSVolume} + static PV + PVC on
 * {@code tank/rke2lab/persist/funnel-cert}) and the restore Job that seeds the saved tailscale
 * funnel state (node key + cert) into the stable-named state Secret.
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

  // The stable proxy-state Secret name (mirrors FunnelStatePersistenceManifestsUnit — both derive
  // it
  // from the SAME PacWebhookFunnel.LEAF, so they cannot drift). The ProxyClass pins TS_KUBE_SECRET
  // to
  // it; the restore seeds it.
  private static final String STATE_SECRET = "ts-" + PacWebhookFunnel.LEAF + "-state";

  /** The stable node name the persist dataset + PV are pinned to (openebs is node-local). */
  private static final String NODE_NAME = "bioskop-mgmt-master";

  /** The openebs deployment namespace ZFSVolume CRs live in. */
  private static final String OPENEBS_NAMESPACE = "openebs";

  private static final String STORAGE_CLASS = "openebs-zfs-persist";

  /** The persist PVC name the restore Job and the backup Job (funnel-state) both mount. */
  public static final String PV_NAME = PacWebhookFunnel.LEAF + "-funnel-cert";

  // A cert-state Secret mirror (tailscale node key + cert) is a few KB; 16Mi is generous headroom.
  private static final String CAPACITY = "16Mi";

  private static final String MIRROR_ENV = "kube/base";
  private static final String MIRROR_CONTAINER = "mirror";
  private static final String SERVICE_ACCOUNT = "funnel-cert-restore";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("tailscale", "funnel-cert-restore");

  private final DataplanLayout layout = DataplanLayout.canonical();

  public FunnelCertRestoreManifestsUnit() {
    // tailscale-system must exist for the Job/PVC/SA (explicit, as TailscaleManifestsUnit declares
    // it);
    // the openebs edge for the ZFSVolume is DERIVED by the planner (zfs.openebs.io CR → installer).
    // This unit MUST NOT depend on the tailscale operator — the operator dependsOn IT, so the
    // Secret
    // is seeded before any proxy is provisioned.
    super(MANIFEST_UNIT_ID, List.of(TailscaleSystemNamespaceManifestsUnit.MANIFEST_UNIT_ID));
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    zfsVolume(scope);
    persistentVolume(scope);
    persistentVolumeClaim(scope);
    serviceAccount(scope);
    role(scope);
    roleBinding(scope);
    restoreJob(scope);
  }

  /** The ZFSVolume CR adopting the pre-declared persist dataset (openebs namespace). */
  private void zfsVolume(final Construct scope) {
    final ApiObject zfsVolume =
        new ApiObject(
            scope,
            "zfsvolume-funnel-cert",
            ApiObjectProps.builder()
                .apiVersion("zfs.openebs.io/v1")
                .kind("ZFSVolume")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(PV_NAME)
                        .namespace(OPENEBS_NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "",
                                Map.of(
                                    ManifestAnnotation.MANIFEST_LAYER.key(),
                                    ManifestLayer.OPERATORS.value())))
                        .build())
                .build());
    // ownerNodeID = the kubernetes node name (openebs is node-local); volumeType=DATASET for a zfs
    // filesystem; poolName = the dataplan persist pool. The dataset is pre-created by ndh from the
    // dataplan — this CR makes openebs adopt it.
    zfsVolume.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "ownerNodeID", NODE_NAME,
                "poolName", layout.persistPool(),
                "capacity", "1073741824",
                "volumeType", "DATASET",
                "fsType", "zfs")));
  }

  /** The static PV bound to the ZFSVolume by volumeHandle — Retain, node-pinned. */
  private void persistentVolume(final Construct scope) {
    final ApiObject pv =
        new ApiObject(
            scope,
            "pv-funnel-cert",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("PersistentVolume")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(PV_NAME)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "",
                                Map.of(
                                    ManifestAnnotation.MANIFEST_LAYER.key(),
                                    ManifestLayer.OPERATORS.value())))
                        .build())
                .build());
    pv.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "capacity",
                Map.of("storage", CAPACITY),
                "accessModes",
                new Object[] {"ReadWriteOnce"},
                "persistentVolumeReclaimPolicy",
                "Retain",
                "storageClassName",
                STORAGE_CLASS,
                "volumeMode",
                "Filesystem",
                "claimRef",
                Map.of(
                    "namespace", NAMESPACE,
                    "name", PV_NAME),
                "csi",
                Map.of(
                    "driver",
                    "zfs.csi.openebs.io",
                    "fsType",
                    "zfs",
                    "volumeHandle",
                    PV_NAME,
                    "volumeAttributes",
                    Map.of("openebs.io/poolname", layout.persistPool())),
                "nodeAffinity",
                Map.of(
                    "required",
                    Map.of(
                        "nodeSelectorTerms",
                        new Object[] {
                          Map.of(
                              "matchExpressions",
                              new Object[] {
                                Map.of(
                                    "key", "openebs.io/nodename",
                                    "operator", "In",
                                    "values", new Object[] {NODE_NAME})
                              })
                        })))));
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
  private void restoreJob(final Construct scope) {
    final String script =
        """
        set -euo pipefail
        if [ -s /persist/state.yaml ]; then
          echo "restoring saved tailscale funnel state into %s"
          kubectl apply -n %s -f /persist/state.yaml
        else
          echo "no saved funnel state on the persist volume — clean first grow"
        fi
        """
            .formatted(STATE_SECRET, NAMESPACE);
    final String floxImage = ManifestSynthesisContext.current().floxDebugPolicy().prodImage();
    final ApiObject jobObject =
        new ApiObject(
            scope,
            "job-funnel-restore",
            ApiObjectProps.builder()
                .apiVersion("batch/v1")
                .kind("Job")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("funnel-cert-restore")
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
                              new Object[] {
                                "flox", "activate", "--dir", "/root", "--", "bash", "-c", script
                              },
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
