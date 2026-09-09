// @codebase
package io.seedmatic.rke2lab.manifests.units.storage;

import io.seedmatic.rke2lab.dataplan.contract.DataplanLayout;
import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.ingress.Component;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

public final class OpenebsZfsManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.STORAGE + "/openebs-zfs";

  private static final String DOMAIN_NAME = "storage";
  private static final String PACKAGE_NAME = "openebs-zfs";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile(DOMAIN_NAME, PACKAGE_NAME);

  public OpenebsZfsManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    final String chartVersion =
        ManifestSynthesisContext.current().componentVersions().of(Component.OPENEBS_ZFS_CHART);
    ApiObject namespace = createNamespace(scope);
    // Two classes off the same ZFS pool: the default exclusive-mount one, and a `shared: yes`
    // variant (bind-mount) so several same-node pods can mount ONE RWO volume at once — needed by
    // Tekton's affinity assistant (a render PipelineRun's assistant + task pods share the `source`
    // PVC). Coherent with the host-provided ZFS (single pool import); RWX-over-nodes stays a future
    // design.
    // Both bind Immediate (not WaitForFirstConsumer): the PV is provisioned the moment the PVC is
    // created, so it Binds without waiting for a consumer pod — a Pending-until-consumer PVC
    // otherwise wedges a Flux `wait: true` Kustomization at Ready=false. Topology-awareness (the
    // reason to defer to first consumer) is moot on a single control node — the volume can only
    // land there.
    // The dataset pools are named from the dataplan SSOT (tank/rke2lab/*) — never a hardcoded ZFS
    // path — the same layout ndh materialises on the host and the persist PV binds against.
    final DataplanLayout layout = DataplanLayout.canonical();
    final String ephemeralPool = layout.controlNodePool("master");
    createStorageClass(scope, "openebs-zfs", true, false, ephemeralPool, "Delete");
    createStorageClass(scope, "openebs-zfs-shared", false, true, ephemeralPool, "Delete");
    // The persist tier: a Retain, non-default class on tank/rke2lab/persist so the funnel cert PV
    // survives a cold-start re-grow (etcd is wiped, so a stable dataset + a static PV are the only
    // cross-grow handle). Nothing lands here unless it names the class explicitly. (The render
    // maven-cache is EPHEMERAL today + goes per-cluster in foundation 3 — not on this tier yet.)
    createStorageClass(scope, "openebs-zfs-persist", false, false, layout.persistPool(), "Retain");
    createHelmChart(scope, namespace, chartVersion);
  }

  private ApiObject createNamespace(final Construct scope) {
    return new ApiObject(
        scope,
        "namespace-openebs",
        ApiObjectProps.builder()
            .apiVersion("v1")
            .kind("Namespace")
            .metadata(
                ApiObjectMetadata.builder()
                    .name("openebs")
                    .annotations(packageProfile.packageAnnotationsWithoutUpstream())
                    .build())
            .build());
  }

  private void createStorageClass(
      final Construct scope,
      final String name,
      final boolean isDefault,
      final boolean shared,
      final String poolName,
      final String reclaimPolicy) {
    final Map<String, String> extraAnnotations =
        isDefault ? Map.of("storageclass.kubernetes.io/is-default-class", "true") : Map.of();
    ApiObject storageClass =
        new ApiObject(
            scope,
            "storageclass-" + name,
            ApiObjectProps.builder()
                .apiVersion("storage.k8s.io/v1")
                .kind("StorageClass")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(name)
                        .annotations(packageProfile.templateAnnotations(extraAnnotations))
                        .build())
                .build());

    // shared=yes → the ZFS dataset is bind-mounted, so several pods on the SAME node can mount one
    // RWO volume concurrently (default is an exclusive device mount → "device already mounted").
    final Map<String, String> parameters =
        shared
            ? Map.of("fstype", "zfs", "poolname", poolName, "shared", "yes")
            : Map.of("fstype", "zfs", "poolname", poolName);
    storageClass.addJsonPatch(
        JsonPatch.add("/allowVolumeExpansion", true),
        JsonPatch.add("/parameters", parameters),
        JsonPatch.add("/provisioner", "zfs.csi.openebs.io"),
        JsonPatch.add("/reclaimPolicy", reclaimPolicy),
        JsonPatch.add("/volumeBindingMode", "Immediate"));
  }

  private void createHelmChart(
      final Construct scope, final ApiObject namespace, final String chartVersion) {
    ApiObject helmChart =
        new ApiObject(
            scope,
            "helmchart-openebs-zfs",
            ApiObjectProps.builder()
                .apiVersion("helm.cattle.io/v1")
                .kind("HelmChart")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name("openebs-zfs")
                        .namespace("openebs")
                        .annotations(packageProfile.packageAnnotationsWithoutUpstream())
                        .build())
                .build());

    helmChart.addDependency(namespace);

    helmChart.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "chart",
                "zfs-localpv",
                "createNamespace",
                true,
                "repo",
                "https://openebs.github.io/zfs-localpv",
                "targetNamespace",
                "openebs",
                "valuesContent",
                "crds:\n  csi:\n    volumeSnapshots:\n      enabled: false\nzfs:\n  bin: /usr/sbin/zfs\nzfsNode:\n  kubeletDir: /var/lib/kubelet\n",
                "version",
                chartVersion)));
  }
}
