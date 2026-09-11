package io.seedmatic.rke2lab.manifests.units.clusterapi;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.contract.profiles.BootstrapIdentity;
import io.seedmatic.rke2lab.manifests.contract.profiles.ImageState;
import io.seedmatic.rke2lab.manifests.contract.profiles.IncusIdentityMaterial;
import io.seedmatic.rke2lab.manifests.contract.profiles.ManagementClusterCaMaterial;
import io.seedmatic.rke2lab.manifests.ingress.Component;
import io.seedmatic.rke2lab.manifests.node.DefaultNodeEnvContext;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import io.seedmatic.rke2lab.netplan.contract.ClusterNetworkBlueprint;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

/**
 * Renders the MANAGEMENT cluster's ADOPTION request onto its own branch ({@code
 * manifests/<host>-mgmt}) — a single {@code ClusterAdoption} CR (the recipe) plus the material the
 * in-cluster {@code rke2-adoption-controller} needs: the four CAPRKE2 BYO-CA Secrets (from {@link
 * ManifestSynthesisContext#managementCas()}) and the CAPN identity Secret.
 *
 * <p>Unlike {@link ClusterApiWorkloadManifestsUnit}, this unit does NOT render the raw CAPI CR-set
 * (Cluster/LXCCluster/RKE2ControlPlane/Machine). It cannot: adopting the RUNNING control plane
 * needs the OWNED {@code Machine}'s {@code ownerReference.uid} pointing at the {@code
 * RKE2ControlPlane}, and that UID is assigned by the API server at creation — unknowable to a
 * GitOps render. So the CR-set is created IN-CLUSTER by the controller (it reads the UID, closes
 * the paused init-race, marks bootstrap done), and seed-master's job here narrows to delivering the
 * DECLARATIVE recipe + the sealed material. The {@code ClusterAdoption} spec is exactly the recipe
 * the controller expands; it generalises verbatim to workload adoption (same CR, {@code
 * controlPlaneReplicas: 3}) — the next phase.
 *
 * <p>The recipe is derived, not configured: the VIP + pod/service CIDRs come from the mgmt
 * cluster's {@link ClusterNetworkBlueprint} (keyed by {@link
 * BootstrapIdentity#clusterNameOrDefault}), the image fingerprint + RKE2 version from the {@link
 * ImageState} the incus scion forwarded, the kube-vip version from {@link Component#KUBE_VIP}.
 * No-op when no {@link ImageState} is bound (a secret-blind render / bare survey): without the
 * fingerprint the recipe would pin a non-existent image, so — like {@link
 * ClusterApiWorkloadManifestsUnit} — the unit renders nothing rather than a misleading placeholder.
 *
 * <p>The BYO-CA + identity Secrets ride the branch sops-encrypted (the git sops clean filter
 * encrypts their {@code data} at commit; Flux decrypts). They are rendered only when their material
 * is revealed (a secret-full render); the controller GUARDS on their presence before it acts, so a
 * secret-blind render that omits them simply leaves the controller waiting. The four BYO-CA are the
 * mgmt cluster's OWN LIVE CA ({@link ManagementClusterCaMaterial}) so CAPRKE2 adopts the running
 * control plane without rotating it.
 */
public final class ClusterApiManagementManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.CLUSTER_API + "/management";

  /** Exploded package dir (relative to the cluster-api domain); diverges from the id segment. */
  public static final String OUTPUT_DIR = "cluster-api-management";

  /** The apiserver + kube-vip control-plane endpoint port. */
  private static final int APISERVER_PORT = 6443;

  /** A management cluster is a SINGLE control node — no ordinal problem, deterministic name. */
  private static final int MANAGEMENT_CONTROL_PLANE_REPLICAS = 1;

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile(ManifestDomainCatalog.CLUSTER_API, OUTPUT_DIR);

  /** The shared CAPI CR builders — here only the BYO-CA + identity Secrets (delegate). */
  private final ClusterApiCrRenderer renderer;

  public ClusterApiManagementManifestsUnit(final ClusterApiCrRenderer renderer) {
    // dependsOn the operator layer: the ClusterAdoption CRD + CAPI/CAPN/CAPRKE2 CRDs the controller
    // targets are registered by ClusterApiOperatorManifestsUnit, so this only dry-runs after that.
    super(MANIFEST_UNIT_ID, List.of(ClusterApiOperatorManifestsUnit.MANIFEST_UNIT_ID));
    this.renderer = renderer;
  }

  @Override
  public String outputDir() {
    return OUTPUT_DIR;
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    final ManifestSynthesisContext synth = ManifestSynthesisContext.current();
    final Optional<ImageState> maybeImage = synth.imageState();
    if (maybeImage.isEmpty()) {
      return;
    }
    final ImageState image = maybeImage.orElseThrow();
    final String cluster =
        synth.bootstrapIdentity().clusterNameOrDefault(DefaultNodeEnvContext.DEFAULT_CLUSTER_NAME);
    final ClusterNetworkBlueprint blueprint =
        ClusterNetworkBlueprint.builder()
            .cluster(cluster)
            .node("master")
            .deriveRecipeModel()
            .build();
    final String vip = blueprint.vip().vipHostInetaddr().getHostAddress();
    final String namespace = "rke2lab-" + cluster;
    // The CAPN identity is PER-REMOTE, keyed by the host (the segment before the -<role> suffix),
    // the SAME Secret the workload path names — one `rke2lab` project, all clusters.
    final int dash = cluster.lastIndexOf('-');
    final String host = dash < 0 ? cluster : cluster.substring(0, dash);
    final String identitySecret = host + "-incus-identity";
    final String rke2Version =
        image.rke2Version().startsWith("v") ? image.rke2Version() : "v" + image.rke2Version();
    final String kubeVipVersion = synth.componentVersions().of(Component.KUBE_VIP);

    final ApiObject namespaceObject = renderer.namespace(scope, cluster, namespace, packageProfile);
    createClusterAdoption(
        scope,
        cluster,
        namespace,
        vip,
        rke2Version,
        kubeVipVersion,
        identitySecret,
        blueprint,
        image,
        namespaceObject);

    // The mgmt cluster's OWN LIVE CA (four BYO-CA Secrets) + the CAPN identity, rendered on the
    // branch sops-encrypted, only when revealed (a secret-full render). The controller guards on
    // their presence; a secret-blind render omits them and the controller waits.
    final Optional<ManagementClusterCaMaterial> cas =
        ManifestSynthesisContext.current().managementCas();
    final Optional<IncusIdentityMaterial> identity =
        ManifestSynthesisContext.current().incusIdentity();
    cas.ifPresent(
        ca ->
            renderer.caSecrets(
                scope,
                cluster,
                namespace,
                ca.serverCa(),
                ca.clientCa(),
                ca.etcdServerCa(),
                ca.etcdPeerCa(),
                packageProfile,
                namespaceObject));
    identity.ifPresent(
        material ->
            renderer.identitySecret(
                scope,
                cluster,
                namespace,
                identitySecret,
                material,
                image.incusProject(),
                packageProfile,
                namespaceObject));
  }

  private ApiObject createClusterAdoption(
      final Construct scope,
      final String cluster,
      final String namespace,
      final String vip,
      final String rke2Version,
      final String kubeVipVersion,
      final String identitySecret,
      final ClusterNetworkBlueprint blueprint,
      final ImageState image,
      final ApiObject namespaceObject) {
    final ApiObject adoption =
        new ApiObject(
            scope,
            "clusteradoption-" + cluster,
            ApiObjectProps.builder()
                .apiVersion("adoption.seedmatic.io/v1alpha1")
                .kind("ClusterAdoption")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(cluster)
                        .namespace(namespace)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "adoption.seedmatic.io|ClusterAdoption|"
                                    + namespace
                                    + "|"
                                    + cluster))
                        .build())
                .build());
    adoption.addDependency(namespaceObject);
    adoption.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "clusterName",
                cluster,
                "namespace",
                namespace,
                "controlPlaneReplicas",
                MANAGEMENT_CONTROL_PLANE_REPLICAS,
                "controlPlaneEndpoint",
                Map.of("host", vip, "port", APISERVER_PORT),
                "clusterNetwork",
                Map.of(
                    "podCIDRs",
                    List.of(blueprint.podCidr()),
                    "serviceCIDRs",
                    List.of(blueprint.serviceCidr()),
                    "serviceDomain",
                    "cluster.local"),
                "image",
                Map.of("fingerprint", image.imageFingerprint()),
                "identitySecretName",
                identitySecret,
                "rke2Version",
                rke2Version,
                "kubeVIPVersion",
                kubeVipVersion)));
    return adoption;
  }
}
