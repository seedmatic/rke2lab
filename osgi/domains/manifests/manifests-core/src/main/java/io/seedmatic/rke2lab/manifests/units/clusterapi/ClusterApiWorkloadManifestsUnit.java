package io.seedmatic.rke2lab.manifests.units.clusterapi;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.contract.WorkloadTarget;
import io.seedmatic.rke2lab.manifests.contract.profiles.ImageState;
import io.seedmatic.rke2lab.manifests.contract.profiles.IncusIdentityMaterial;
import io.seedmatic.rke2lab.manifests.contract.profiles.WorkloadClusterCasMaterial;
import io.seedmatic.rke2lab.manifests.ingress.Component;
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
 * Renders the {@code ClusterProvision} INTENT (recipe) for each WORKLOAD cluster onto the MANAGEMENT
 * cluster's own branch ({@code manifests/<host>-mgmt}) — model B: the CR lives where CAPI runs, so
 * the management cluster's Flux applies it and the in-cluster {@code seed-incluster} controller
 * reconciles it adopt-first into a DIFFERENT cluster ({@code <host>-wrkld}). There is no imperative
 * {@code kubectl apply} and no {@code -wrkld}-branch CRs (that branch carries only the workload's
 * own app stack).
 *
 * <p>This unit no longer renders the raw CAPI CR-set (Cluster/LXCCluster/RKE2ControlPlane/
 * MachineDeployment). That set is materialised IN-CLUSTER by {@code seed-incluster} from the
 * {@code ClusterProvision} — the piece GitOps cannot pre-set (the owned Machines' ownerRef UID + the
 * adopt-vs-provision decision are in-cluster facts). So this unit's job narrows to the DECLARATIVE
 * recipe + the credentials the controller expands the CR-set from.
 *
 * <p>The render subject stays {@link ManifestSynthesisContext#bootstrapIdentity()} (the management
 * cluster); the workload clusters ride beside it as {@link
 * ManifestSynthesisContext#workloadTargets()} (the manifests-facet sub-facet). For each target this
 * unit derives that cluster's whole {@link ClusterNetworkBlueprint} from its {@link
 * WorkloadTarget#clusterName()} — pod/service CIDRs, the kube-vip VIP — pins the image to {@link
 * ImageState#imageFingerprint()} and the RKE2 version to {@link ImageState#rke2Version()} (the
 * node-base identity the incus scion forwarded), and lists the control-plane pets ({@code
 * master + peer1 + peer2} = 3 etcd members; peer3 dropped for workloads — a workload is NOT the full
 * CANONICAL 4-server topology). Workers are a follow-up (none listed yet).
 *
 * <p>No-op when there are no targets (a mgmt-only / standalone run) or when no {@link ImageState}
 * is bound (a secret-blind in-cluster render / a bare survey): without the image fingerprint the
 * recipe would pin a non-existent image, so — like {@link ImageStateConfigMapManifestsUnit} — the
 * unit renders nothing rather than a misleading placeholder.
 *
 * <p>The per-remote CAPN identity Secret {@code <host>-incus-identity} (foundation 5) and the four
 * CAPRKE2 BYO-CA Secrets are rendered HERE ON THE BRANCH via the shared {@link ClusterApiCrRenderer}
 * (the SAME collaborator {@link ClusterApiManagementManifestsUnit} uses), sops-encrypted, when their
 * material is revealed (a secret-full render). One {@code rke2lab} incus project (foundation 4
 * dropped — instance names are globally unique via the blueprint), so the Secret carries {@code
 * project: rke2lab}.
 */
public final class ClusterApiWorkloadManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.CLUSTER_API + "/workload";

  /** Exploded package dir (relative to the cluster-api domain); diverges from the id segment. */
  public static final String OUTPUT_DIR = "cluster-api-workload";

  /** The apiserver + kube-vip control-plane endpoint port. */
  private static final int APISERVER_PORT = 6443;

  /**
   * A workload's HA control plane = {@code master + peer1 + peer2} = 3 etcd members. NOT the raw
   * CANONICAL server count (4: peer3 is dropped for workloads), per the completion plan.
   */
  private static final int WORKLOAD_CONTROL_PLANE_REPLICAS = 3;

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile(ManifestDomainCatalog.CLUSTER_API, OUTPUT_DIR);

  /** The shared CAPI CR builders, handed in at construction (delegate, not a static helper). */
  private final ClusterApiCrRenderer renderer;

  public ClusterApiWorkloadManifestsUnit(final ClusterApiCrRenderer renderer) {
    // dependsOn the operator layer: the CAPI/CAPN/CAPRKE2 CRDs these CRs target are registered by
    // ClusterApiOperatorManifestsUnit, so this only dry-runs after that layer is healthy.
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
    final List<WorkloadTarget> targets = synth.workloadTargets();
    final Optional<ImageState> maybeImage = synth.imageState();
    if (targets.isEmpty() || maybeImage.isEmpty()) {
      return;
    }
    final ImageState image = maybeImage.orElseThrow();
    for (final WorkloadTarget target : targets) {
      renderTarget(scope, target, image);
    }
  }

  private void renderTarget(
      final Construct scope, final WorkloadTarget target, final ImageState image) {
    final String cluster = target.clusterName();
    final ClusterNetworkBlueprint blueprint =
        ClusterNetworkBlueprint.builder()
            .cluster(cluster)
            .node("master")
            .deriveRecipeModel()
            .build();
    final String vip = blueprint.vip().vipHostInetaddr().getHostAddress();
    final String namespace = "rke2lab-" + cluster;
    // The CAPN identity is PER-REMOTE, not per-cluster: every cluster on a bare-metal shares the
    // one
    // `rke2lab` project (instance names are globally unique via the blueprint), so the Secret is
    // keyed by the host (bioskop, nikopol). Rendered below, in THIS namespace.
    final String identitySecret = target.host() + "-incus-identity";
    // CAPI/CAPRKE2 want the k8s version with a leading `v`; the nix-emitted rke2Version has none
    // (e.g. `1.34.8+rke2r2`) — prefix it iff absent.
    final String rke2Version =
        image.rke2Version().startsWith("v") ? image.rke2Version() : "v" + image.rke2Version();
    final String kubeVipVersion =
        ManifestSynthesisContext.current().componentVersions().of(Component.KUBE_VIP);
    // The workload's Incus remote — its host's engine (bioskop-nixos / nikopol-nixos). Intent
    // value;
    // the controller/CAPN authenticate from the identity Secret (which also carries `server`).
    final String remoteEndpoint = "https://" + target.host() + "-nixos:8443";

    final ApiObject namespaceObject = renderer.namespace(scope, cluster, namespace, packageProfile);
    createClusterProvision(
        scope,
        cluster,
        namespace,
        vip,
        rke2Version,
        kubeVipVersion,
        identitySecret,
        remoteEndpoint,
        blueprint,
        image,
        namespaceObject);

    // The CREDENTIALS seed-incluster expands the CR-set with, rendered ON THE BRANCH
    // sops-encrypted,
    // only when their material is revealed (a secret-full render): the per-remote CAPN identity and
    // the four CAPRKE2 BYO-CA Secrets (so CAPRKE2 delivers OUR mammoth-skate CA to the workload
    // node
    // instead of self-generating). A secret-blind render must not run steady-state, else it pushes
    // them empty and Flux prunes the populated ones.
    final Optional<IncusIdentityMaterial> identity =
        ManifestSynthesisContext.current().incusIdentity();
    final Optional<WorkloadClusterCasMaterial.Entry> workloadCa =
        ManifestSynthesisContext.current().workloadCas().flatMap(cas -> cas.forCluster(cluster));
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
    workloadCa.ifPresent(
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
  }

  // The ClusterProvision INTENT (recipe) for a workload — the Flux-owned CR that seed-incluster
  // reconciles adopt-first into the CAPI CR-set (Cluster/LXCCluster/RKE2ControlPlane + the owned
  // Machines). Every value derives from the blueprint SSOT (VIP, CIDRs) + the node-base ImageState;
  // the controller templates, it never computes addressing. Control-plane pets are
  // master+peer1+peer2 (WORKLOAD_CONTROL_PLANE_REPLICAS; peer3 dropped — a workload is NOT the full
  // CANONICAL 4-server topology). Workers are a follow-up (none listed yet).
  private void createClusterProvision(
      final Construct scope,
      final String cluster,
      final String namespace,
      final String vip,
      final String rke2Version,
      final String kubeVipVersion,
      final String identitySecret,
      final String remoteEndpoint,
      final ClusterNetworkBlueprint blueprint,
      final ImageState image,
      final ApiObject namespaceObject) {
    final List<Object> nodes =
        ClusterNetworkBlueprint.CANONICAL_NODE_NAMES.stream()
            .limit(WORKLOAD_CONTROL_PLANE_REPLICAS)
            .map(node -> (Object) Map.of("name", cluster + "-" + node, "role", "control-plane"))
            .toList();
    final ApiObject provision =
        new ApiObject(
            scope,
            "clusterprovision-" + cluster,
            ApiObjectProps.builder()
                .apiVersion("cluster.seedmatic.io/v1alpha1")
                .kind("ClusterProvision")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(cluster)
                        .namespace(namespace)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "cluster.seedmatic.io|ClusterProvision|"
                                    + namespace
                                    + "|"
                                    + cluster))
                        .build())
                .build());
    provision.addDependency(namespaceObject);
    provision.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "clusterName",
                cluster,
                "namespace",
                namespace,
                "kind",
                "workload",
                "controlPlaneEndpoint",
                Map.of("host", vip, "port", APISERVER_PORT),
                "clusterNetwork",
                Map.of(
                    "podCIDRs", List.of(blueprint.podCidr()),
                    "serviceCIDRs", List.of(blueprint.serviceCidr()),
                    "serviceDomain", "cluster.local"),
                "image",
                Map.of("fingerprint", image.imageFingerprint()),
                "rke2Version",
                rke2Version,
                "kubeVIPVersion",
                kubeVipVersion,
                "remote",
                Map.of("endpoint", remoteEndpoint, "identitySecretName", identitySecret),
                "nodes",
                nodes)));
  }
}
