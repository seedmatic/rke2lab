// @codebase
package io.seedmatic.rke2lab.manifests.units.mesh;

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
 * The funnel-state PROXYCLASS + BACKUP half of the durable-funnel fix — the twin of {@link
 * FunnelCertRestoreManifestsUnit} (which owns the persist volume + the restore that SEEDS the state
 * Secret before the operator runs). See {@code
 * docs/architecture/cluster-api/pac-in-cluster-render-spec.adoc} § funnel-durability.
 *
 * <p>This unit renders, AFTER the tailscale operator (it {@code dependsOn} it — the {@code
 * ProxyClass} is a {@code tailscale.com} CR needing the operator's CRD, and the backup only runs
 * once the proxy is up):
 *
 * <ol>
 *   <li>a {@link #proxyClass} pinning {@code TS_KUBE_SECRET} to a STABLE name so the state Secret
 *       is addressable across grows (the funnel Ingress opts in via {@code
 *       tailscale.com/proxy-class});
 *   <li>a {@link #backupJob} that mirrors the current state Secret back to the persist volume once
 *       the funnel CERT is present — it waits on the cert key ITSELF (not the Secret, not pod
 *       readiness), so it never overwrites a good backup with a cert-less state.
 * </ol>
 *
 * <p>The restore ran far up-chain (funnel-cert-restore, before the operator), so by the time this
 * backup runs the restore Job is long gone — the two never contend for the one RWO persist PVC
 * (which funnel-cert-restore owns; the backup just mounts it by name).
 */
public final class FunnelStatePersistenceManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.INGRESS + "/funnel-state";

  private static final String NAMESPACE = MeshRefs.MESH_SYSTEM_NAMESPACE.name();

  /** The stable proxy-state Secret name the ProxyClass pins TS_KUBE_SECRET to (vs the pod name). */
  public static final String STATE_SECRET = "ts-" + PacWebhookFunnel.LEAF + "-state";

  /** The ProxyClass the funnel Ingress opts into via tailscale.com/proxy-class. */
  public static final String PROXY_CLASS = PacWebhookFunnel.LEAF;

  /** The persist PVC (owned by FunnelCertRestoreManifestsUnit) the backup Job mounts. */
  private static final String PV_NAME = PacWebhookFunnel.LEAF + "-funnel-cert";

  private static final String MIRROR_ENV = "kube/base";
  private static final String MIRROR_CONTAINER = "mirror";
  private static final String SERVICE_ACCOUNT = "funnel-state";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile("mesh", "funnel-state");

  public FunnelStatePersistenceManifestsUnit() {
    // The Tailscale operator registers the tailscale.com CRD the ProxyClass needs, and the backup
    // only makes sense once the proxy is up — so this unit lands AFTER the operator. (The operator
    // in
    // turn dependsOn funnel-cert-restore, so the restore's seed precedes the proxy: no cycle,
    // because
    // the restore was split OUT of this unit.)
    super(MANIFEST_UNIT_ID, List.of(TailscaleManifestsUnit.MANIFEST_UNIT_ID));
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    proxyClass(scope);
    serviceAccount(scope);
    role(scope);
    roleBinding(scope);
    backupJob(scope);
  }

  /** ProxyClass pinning TS_KUBE_SECRET to a stable name — cluster-scoped. */
  private void proxyClass(final Construct scope) {
    final ApiObject proxyClass =
        new ApiObject(
            scope,
            "proxyclass-pac-webhook",
            ApiObjectProps.builder()
                .apiVersion("tailscale.com/v1alpha1")
                .kind("ProxyClass")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(PROXY_CLASS)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "",
                                Map.of(
                                    ManifestAnnotation.MANIFEST_LAYER.key(),
                                    ManifestLayer.OPERATORS.value())))
                        .build())
                .build());
    // The operator appends ProxyClass env AFTER its own, and a later value wins (verified in the
    // operator's sts.go), so this overrides the pod-named default → the state Secret is stable.
    proxyClass.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "statefulSet",
                Map.of(
                    "pod",
                    Map.of(
                        "tailscaleContainer",
                        Map.of(
                            "env",
                            new Object[] {
                              Map.of("name", "TS_KUBE_SECRET", "value", STATE_SECRET)
                            }))))));
  }

  private void serviceAccount(final Construct scope) {
    new ApiObject(
        scope,
        "sa-funnel-state",
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
   * Namespaced Role for the backup: read the state Secret. {@code get} for the {@code -o yaml} dump
   * + {@code list}/{@code watch} for {@code kubectl wait --for=create}/{@code --for=jsonpath}
   * (which open an informer). No write verbs — the backup never mutates the Secret, only mirrors it
   * out.
   */
  private void role(final Construct scope) {
    final ApiObject role =
        new ApiObject(
            scope,
            "role-funnel-state",
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
                  "verbs", new Object[] {"get", "list", "watch"})
            }));
  }

  private void roleBinding(final Construct scope) {
    final ApiObject binding =
        new ApiObject(
            scope,
            "rolebinding-funnel-state",
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
   * Backup Job (workloads layer): mirror the current state Secret to the persist volume, stripped
   * of server-set metadata so it re-applies cleanly. It must wait for the funnel CERT — not merely
   * the Secret, and not the proxy pod: readiness does not gate on the cert (measured, the cert is
   * written tens of seconds AFTER the pod is Ready). {@code set -e} plus the cert wait give the fix
   * its teeth: on a timeout the write below never runs, so a good backup is never clobbered with a
   * cert-less state. On a re-grow the seeded Secret already carries a valid cert (the proxy reuses
   * it, zero ACME issuance) and the wait returns at once.
   */
  private void backupJob(final Construct scope) {
    final String script =
        """
        set -euo pipefail
        ns=%s
        secret=%s
        echo "waiting for the funnel state secret ${secret} to exist"
        kubectl wait --for=create -n "$ns" "secret/${secret}" --timeout=300s
        echo "waiting for the proxy device to register (device_fqdn)"
        kubectl wait --for=jsonpath='{.data.device_fqdn}' -n "$ns" "secret/${secret}" --timeout=300s
        # The cert key is <device_fqdn>.crt; derive the FQDN at runtime (no tailnet name hardcoded)
        # and escape its dots for the jsonpath. Waiting on the cert key ITSELF is load-bearing — see
        # the method javadoc for why the Secret / pod-Ready are the wrong signal. Pure bash (strip the
        # trailing dot, escape dots) — the kube/base FloxEnv has bash + coreutils but NOT sed.
        fqdn="$(kubectl get -n "$ns" secret "$secret" -o jsonpath='{.data.device_fqdn}' | base64 -d)"
        # The strip below is doubled on purpose: this whole string is String.formatted(...), which
        # reads a lone percent as a format conversion, so a literal one must be written twice.
        key="${fqdn%%.}.crt"
        esc="${key//./\\\\.}"
        echo "waiting for the funnel cert ${key} to be issued and written"
        kubectl wait --for="jsonpath={.data.${esc}}" -n "$ns" "secret/${secret}" --timeout=900s
        kubectl get -n "$ns" secret "$secret" -o yaml | yq 'del(.metadata.managedFields) | del(.metadata.resourceVersion) | del(.metadata.uid) | del(.metadata.creationTimestamp) | del(.metadata.ownerReferences) | del(.metadata.annotations."kubectl.kubernetes.io/last-applied-configuration") | del(.status)' > /persist/state.yaml
        echo "backed up funnel state (with cert) to the persist volume"
        """
            .formatted(NAMESPACE, STATE_SECRET);
    final String floxImage = ManifestSynthesisContext.current().floxDebugPolicy().prodImage();
    final ApiObject jobObject =
        new ApiObject(
            scope,
            "job-funnel-backup",
            ApiObjectProps.builder()
                .apiVersion("batch/v1")
                .kind("Job")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("funnel-cert-backup")
                        .namespace(NAMESPACE)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "",
                                Map.of(
                                    ManifestAnnotation.MANIFEST_LAYER.key(),
                                    ManifestLayer.WORKLOADS.value())))
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
                    // The flox NRI plugin only puts the env on PATH for a container NAMED by a
                    // flox.seedmatic.io/environment.<c> annotation — opt the mirror container into
                    // kube/base (kubectl + yq-go).
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
