package io.seedmatic.rke2lab.manifests.units.clusterapi;

import io.seedmatic.rke2lab.incus.ingress.NodeDeviceSet;
import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ClusterCoordinate;
import io.seedmatic.rke2lab.manifests.contract.ClusterRole;
import io.seedmatic.rke2lab.manifests.contract.ControlPlaneShape;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.contract.profiles.ImageState;
import io.seedmatic.rke2lab.manifests.contract.profiles.IncusIdentityMaterial;
import io.seedmatic.rke2lab.manifests.contract.profiles.WorkloadClusterCasMaterial;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import io.seedmatic.rke2lab.netplan.contract.ClusterNetworkBlueprint;
import java.util.List;
import java.util.Optional;
import org.cdk8s.ApiObject;
import software.constructs.Construct;

/**
 * Renders the {@code ClusterIntention} + {@code PoolIntention} intent (the 2×2 decomposition) for
 * every cluster this plane OWNS — its CHILDREN — onto its own branch ({@code
 * manifests/<host>-mgmt}): model B, the CRs live where CAPI runs, so this cluster's Flux applies
 * them and the in-cluster {@code seed-incluster} controller reconciles them adopt-first into a
 * DIFFERENT cluster. There is no imperative {@code kubectl apply} and no {@code -wrkld}-branch CRs
 * (that branch carries only the workload's own app stack).
 *
 * <p>⚠️ "Workload" in this class's name is a LEFTOVER, and the same lie {@code workloadTargets}
 * carried: a child can be a MANAGEMENT cluster ({@code nikopol-mgmt}, birthed by {@code
 * bioskop-mgmt}), and this unit renders it — switching shape on {@link ClusterCoordinate#role()}.
 * The name is due to follow; {@link #OUTPUT_DIR} is NOT, because {@code cluster-api-workload} is
 * the path already written into every rendered branch and into Flux's kustomizations, so renaming
 * it would make Flux prune and recreate the whole package.
 *
 * <p>This unit no longer renders the raw CAPI CR-set (Cluster/LXCCluster/RKE2ControlPlane/
 * MachineDeployment). That set is materialised IN-CLUSTER by {@code seed-incluster} from the {@code
 * ClusterIntention} (→ Cluster/LXCCluster) and the {@code PoolIntention} children (→
 * RKE2ControlPlane + templates + the owned per-pet Machines) — the piece GitOps cannot pre-set (the
 * owned Machines' ownerRef UID + the adopt-vs-provision decision are in-cluster facts). So this
 * unit's job narrows to the DECLARATIVE recipe + the credentials the controller expands the CR-set
 * from.
 *
 * <p>The render subject stays {@link ManifestSynthesisContext#bootstrapIdentity()} (this plane);
 * its children ride beside it as {@link ManifestSynthesisContext#ownedChildren()} — DERIVED by the
 * owner rule from the declared fleet, never enumerated. For each child this unit derives that
 * cluster's whole {@link ClusterNetworkBlueprint} from its {@link ClusterCoordinate#clusterName()}
 * — pod/service CIDRs, the kube-vip VIP — pins the image to {@link ImageState#imageFingerprint()}
 * and the RKE2 version to {@link ImageState#rke2Version()} (the node-base identity the incus scion
 * forwarded), and lists the control-plane pets ({@code master + peer1 + peer2} = 3 etcd members for
 * a workload; peer3 dropped — a workload is NOT the full CANONICAL 4-server topology — and ONE for
 * a management child). Workers are a follow-up (none listed yet).
 *
 * <p>No-op when this plane owns no child (a workload cluster, a bare survey, the standalone CLI) or
 * when no {@link ImageState} is bound (a secret-blind in-cluster render): without the realised
 * image the recipe would reference a {@code NodeImage} that describes nothing, so the unit renders
 * nothing rather than a misleading placeholder.
 *
 * <p>The per-remote CAPN identity Secret {@code <host>-incus-identity} (foundation 5), the four
 * CAPRKE2 BYO-CA Secrets and the child's {@code <cluster>-server-manifests} bootstrap bundle are
 * rendered HERE ON THE BRANCH via the shared {@link ClusterApiCrRenderer} (the SAME collaborator
 * {@link ClusterApiManagementManifestsUnit} uses), sops-encrypted, when their material is present
 * (a secret-full render). One {@code rke2lab} incus project (foundation 4 dropped — instance names
 * are globally unique via the blueprint), so the Secret carries {@code project: rke2lab}.
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
  // The control-plane SHAPE is derived from the target's role, not fixed: a workload takes
  // master+peer1+peer2 (peer3 dropped — a workload is NOT the full CANONICAL topology), while a
  // MANAGEMENT cluster is ONE control node. This unit renders any CHILD cluster, and since
  // 2026-09-27 a child can be a management cluster itself (model B: bioskop-mgmt births
  // nikopol-mgmt, which then self-adopts). Leaving these as workload constants would have grown a
  // three-node nikopol-mgmt announcing itself as a workload — right name, wrong shape.

  private static final int MANAGEMENT_CONTROL_PLANE_REPLICAS = 1;

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
    // EVERY cluster the fleet declares except the SUBJECT, whose own intention is the management
    // unit's. Not `ownedChildren()`: the federation view is UNIFORM, so a plane's branch carries
    // the
    // declaration for the whole fleet and the plane simply does not ACT on what it does not adopt
    // (the controller gates on `adoptedBy`). Material still follows the adopter — see renderChild.
    final String subject = synth.bootstrapIdentity().clusterName();
    final List<ClusterCoordinate> others =
        synth.fleetClusters().stream().filter(c -> !c.clusterName().equals(subject)).toList();
    final Optional<ImageState> maybeImage = synth.imageState();
    if (others.isEmpty() || maybeImage.isEmpty()) {
      return;
    }
    final ImageState image = maybeImage.orElseThrow();
    for (final ClusterCoordinate other : others) {
      renderChild(scope, other, image, subject);
    }
  }

  private void renderChild(
      final Construct scope,
      final ClusterCoordinate child,
      final ImageState image,
      final String subject) {
    final String cluster = child.clusterName();
    // ★ The role is read off the coordinate, already TYPED: it was parsed once, loudly, where the
    // fleet was decoded. Re-parsing it from the composed name here would be a round-trip through
    // `ClusterRole.of`, whose catch-all answers MGMT for anything it cannot read — what let the
    // netplan projection pass hosts as cluster names and lose half the clusters in silence.
    final ClusterRole role = child.role();
    // A management cluster is ONE control node by construction; a workload takes the DECLARED shape
    // (`single` | `ha`), which replaces a hardcoded 3. The shape is a word rather than a number
    // because 1 and 3 are not arbitrary points on a scale — see ControlPlaneShape.
    final int controlPlaneReplicas =
        switch (role) {
          case MGMT -> MANAGEMENT_CONTROL_PLANE_REPLICAS;
          case WRKLD ->
              ManifestSynthesisContext.current()
                  .workloadControlPlane()
                  .orElse(ControlPlaneShape.HA)
                  .replicas();
        };
    // The federated role recorded on the ClusterIntention — the SAME vocabulary the mgmt unit uses
    // for its self-adoption, so a birthed management cluster is indistinguishable from one that
    // adopted itself.
    final String federatedKind =
        switch (role) {
          case MGMT -> "management";
          case WRKLD -> "workload";
        };
    // The child's single adopter — DERIVED, not assumed to be this plane: the derivation is the one
    // place that rule lives, and ClusterFleetTest pins that it cannot disagree with ownedBy.
    final Optional<String> maybeAdopter = ManifestSynthesisContext.current().adopterOf(cluster);
    if (maybeAdopter.isEmpty()) {
      return;
    }
    final String adoptedBy = maybeAdopter.orElseThrow();
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
    final String identitySecret = child.host() + "-incus-identity";
    // ★ The endpoint belongs to the READER, not to the cluster being described — the one field on
    // this CR that is genuinely POSITIONAL. Incus is CLUSTERED (measured 2026-09-30: bioskop-nixos
    // database-leader and nikopol-nixos database-client, both ONLINE, one cluster), so a client
    // talks
    // to ANY member it can reach and `spec.target` decides PLACEMENT. Reaching the target member
    // directly is neither required nor possible: from bioskop's cluster nixos.nikopol
    // (172.16.16.1) times out, while nixos.bioskop (172.16.0.1) is open, and the mirror from
    // nikopol's.
    //
    // ⚠️ So this is derived from the RENDER SUBJECT — the cluster whose branch this is, hence whose
    // plane will reconcile it — and NOT from the child. Deriving it from the child is a mistake I
    // made and measured: it sent bioskop's CAPN to 172.16.16.1:8443 on every LXCCluster reconcile
    // for nikopol-mgmt, and its birth stalled at `0/1 present, 1 pending`. The original code passed
    // "" here and fell back to the identity Secret's `server` (the local engine, since the local
    // host minted it) — which was RIGHT for a parent's copy, and wrong only for a sub-plane's own
    // copy, because that Secret was minted by the parent and copied verbatim.
    //
    // The fabric FQDN, not the bare host name: CAPN dials this from a POD, and a pod resolves
    // through
    // CoreDNS — where the bare name reached the vmnet bridge's dnsmasq, which answered from the
    // host's /etc/hosts (127.0.0.2), so CAPN dialled its own :8443 and reported "certificate is
    // valid
    // for localhost". See ClusterNetworkBlueprint.NamePlan.
    final String remoteEndpoint =
        "https://"
            + ClusterNetworkBlueprint.builder()
                .cluster(subject)
                .node("master")
                .deriveRecipeModel()
                .build()
                .names()
                .nixosFabricFqdn()
            + ":8443";

    final ApiObject namespaceObject = renderer.namespace(scope, cluster, namespace, packageProfile);
    // The realised image, described to the cluster that boots on it — the pool references it by
    // name.
    renderer.nodeImage(scope, cluster, namespace, image, packageProfile, namespaceObject);
    // The 2×2 intent: ONE cluster-level ClusterIntention + N pool-level PoolIntention (here just
    // the
    // control-node pool; worker pools are a follow-up). Both are Flux-owned and Flux-pruned;
    // seed-incluster's reconcilers own the derived CAPI CR-set. Pets = master+peer1+peer2
    // (WORKLOAD_CONTROL_PLANE_REPLICAS; peer3 dropped — a workload is NOT the full CANONICAL
    // topology).
    final ApiObject clusterIntention =
        renderer.clusterIntention(
            scope,
            cluster,
            namespace,
            federatedKind,
            vip,
            APISERVER_PORT,
            List.of(blueprint.podCidr()),
            List.of(blueprint.serviceCidr()),
            remoteEndpoint,
            identitySecret,
            adoptedBy,
            packageProfile,
            namespaceObject);
    final List<String> pets =
        ClusterNetworkBlueprint.CANONICAL_NODE_NAMES.stream()
            .limit(controlPlaneReplicas)
            .map(node -> cluster + "-" + node)
            .toList();
    renderer.controlNodePoolIntention(
        scope,
        cluster,
        namespace,
        vip,
        APISERVER_PORT,
        image.rke2Version(),
        ClusterApiCrRenderer.nodeImageName(cluster, image),
        NodeDeviceSet.forCluster(
                ManifestSynthesisContext.current().fabricBridgeParent(),
                blueprint.vmnetBridgeName())
            .toCapnSpecs(),
        pets,
        blueprint.names().incusMember(),
        adoptedBy,
        packageProfile,
        clusterIntention);

    // The CREDENTIALS seed-incluster expands the CR-set with, rendered ON THE BRANCH
    // sops-encrypted,
    // only when their material is revealed (a secret-full render): the per-remote CAPN identity and
    // the four CAPRKE2 BYO-CA Secrets (so CAPRKE2 delivers OUR mammoth-skate CA to the workload
    // node
    // instead of self-generating). A secret-blind render must not run steady-state, else it pushes
    // them empty and Flux prunes the populated ones.
    // ⚠️ MATERIAL follows the ADOPTER, unlike the declaration above which is uniform. A plane holds
    // credentials only for what it adopts: carrying a cluster's CA or admin credential where it has
    // no business acting is how a child's branch came to hold its PARENT's CA and then its parent's
    // admin certificate, each rendered from a run-scoped material onto a subject that was not the
    // run's cluster (instances 5 and 6 of the viewpoint family).
    if (!ManifestSynthesisContext.current().adopts(cluster)) {
      return;
    }
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
                remoteEndpoint,
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
    // The target's own bootstrap bundle, carved by its per-target render pass earlier in THIS run
    // (never revealed from the cellar — see WorkloadBootstrapBundlesMaterial). Absent on a pass
    // that
    // rendered no target branch (a survey / the standalone CLI), and then the Secret is simply not
    // rendered: seed-incluster's material gate keeps the pool honestly waiting rather than
    // provisioning a node whose CNI cannot come up.
    ManifestSynthesisContext.current()
        .workloadBootstrapBundles()
        .flatMap(bundles -> bundles.forCluster(cluster))
        .ifPresent(
            bundle ->
                renderer.serverManifestsSecret(
                    scope,
                    cluster,
                    namespace,
                    bundle.manifests(),
                    packageProfile,
                    namespaceObject));
  }
}
