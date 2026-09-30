package io.seedmatic.rke2lab.manifests.units.clusterapi;

import io.seedmatic.rke2lab.incus.ingress.NodeDeviceSet;
import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.contract.profiles.BootstrapIdentity;
import io.seedmatic.rke2lab.manifests.contract.profiles.ImageState;
import io.seedmatic.rke2lab.manifests.contract.profiles.IncusIdentityMaterial;
import io.seedmatic.rke2lab.manifests.contract.profiles.ManagementClusterCaMaterial;
import io.seedmatic.rke2lab.manifests.contract.profiles.WorkloadClusterCasMaterial;
import io.seedmatic.rke2lab.manifests.node.DefaultNodeEnvContext;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import io.seedmatic.rke2lab.netplan.contract.ClusterNetworkBlueprint;
import java.util.List;
import java.util.Optional;
import org.cdk8s.ApiObject;
import software.constructs.Construct;

/**
 * Renders the MANAGEMENT cluster's SELF-adoption intent onto its own branch ({@code
 * manifests/<host>-mgmt}) — the 2×2 intent (a cluster-level {@code ClusterIntention} + a single-pet
 * control-node {@code PoolIntention}, a management cluster being ONE control node) plus the
 * material the in-cluster {@code seed-incluster} needs: the four CAPRKE2 BYO-CA Secrets (from
 * {@link ManifestSynthesisContext#managementCas()}) and the CAPN identity Secret. Identical intent
 * to {@link ClusterApiWorkloadManifestsUnit} (both delegate to {@link ClusterApiCrRenderer}); the
 * mgmt case only differs by kind ({@code management}), one pet, and a local (empty) remote
 * endpoint.
 *
 * <p>Neither unit renders the raw CAPI CR-set (Cluster/LXCCluster/RKE2ControlPlane/Machine). It
 * cannot: adopting the RUNNING control plane needs the OWNED {@code Machine}'s {@code
 * ownerReference.uid} pointing at the {@code RKE2ControlPlane}, and that UID is assigned by the API
 * server at creation — unknowable to a GitOps render. So the CR-set is materialised IN-CLUSTER by
 * the controller (it reads the UID, closes the paused init-race, marks bootstrap done), and
 * seed-master's job here narrows to delivering the DECLARATIVE intent + the sealed material.
 *
 * <p>The recipe is derived, not configured: the VIP + pod/service CIDRs come from the mgmt
 * cluster's {@link ClusterNetworkBlueprint} (keyed by {@link
 * BootstrapIdentity#clusterNameOrDefault}), the image fingerprint + RKE2 version from the {@link
 * ImageState} the incus scion forwarded. No-op when no {@link ImageState} is bound (a secret-blind
 * render / bare survey): without the fingerprint the recipe would pin a non-existent image, so —
 * like {@link ClusterApiWorkloadManifestsUnit} — the unit renders nothing rather than a misleading
 * placeholder.
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
    // The subject's single adopter. Absent fleet ⟹ render NOTHING: an intention without an adopter
    // is what let a sub-plane adopt itself by recognising its own name.
    final Optional<String> maybeAdopter = synth.adopterOf(cluster);
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
    // The CAPN identity is PER-REMOTE, keyed by the host (the segment before the -<role> suffix),
    // the SAME Secret the workload path names — one `rke2lab` project, all clusters.
    final int dash = cluster.lastIndexOf('-');
    final String host = dash < 0 ? cluster : cluster.substring(0, dash);
    final String identitySecret = host + "-incus-identity";

    final ApiObject namespaceObject = renderer.namespace(scope, cluster, namespace, packageProfile);
    // The realised image, described to the cluster that boots on it — the pool references it by
    // name.
    renderer.nodeImage(scope, cluster, namespace, image, packageProfile, namespaceObject);
    // The 2×2 intent for the mgmt cluster's SELF-adoption: a cluster-level ClusterIntention + a
    // single-pet control-node PoolIntention (a management cluster is ONE control node). kind =
    // management records the federated role (birthed/adopted identically to a workload).
    //
    // The remote endpoint is STATED, derived from this cluster's OWN blueprint — exactly as the
    // workload path does for a child. It used to be left empty, on the reading that "the Incus
    // engine is local, so resolve it from the identity Secret's `server`". That reading holds only
    // for the ROOT plane, and by accident: the Secret's `server` names the host that MINTED it, and
    // for the root that happens to be its own host.
    //
    // ⚠️ A sub-plane breaks it. Measured 2026-09-29: `nikopol-mgmt`'s own intention carried
    // `endpoint: ""`, its `nikopol-incus-identity` Secret says `https://nixos.bioskop:8443`
    // (bioskop
    // minted it), so its CAPN dialled bioskop — `172.16.0.1`, i/o timeout — while `nixos.nikopol`
    // (`172.16.16.1`) answered OPEN from inside that very cluster. Its PARENT's copy of the same
    // intention was correct precisely BECAUSE it states the endpoint explicitly.
    //
    // Same defect shape as the roster the SELF branch used to take from the seed: a value that is
    // right from the renderer's viewpoint and wrong from the subject's. Stating it removes the
    // viewpoint from the answer.
    final String remoteEndpoint = "https://" + blueprint.names().nixosFabricFqdn() + ":8443";
    final List<String> pets =
        ClusterNetworkBlueprint.CANONICAL_NODE_NAMES.stream()
            .limit(MANAGEMENT_CONTROL_PLANE_REPLICAS)
            .map(node -> cluster + "-" + node)
            .toList();
    final ApiObject clusterIntention =
        renderer.clusterIntention(
            scope,
            cluster,
            namespace,
            "management",
            vip,
            APISERVER_PORT,
            List.of(blueprint.podCidr()),
            List.of(blueprint.serviceCidr()),
            remoteEndpoint,
            identitySecret,
            adoptedBy,
            packageProfile,
            namespaceObject);
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

    // The four BYO-CA Secrets + the CAPN identity, rendered on the branch sops-encrypted, only when
    // revealed (a secret-full render). The controller guards on their presence; a secret-blind
    // render omits them and the controller waits.
    //
    // ⚠️ The CA is chosen by the render SUBJECT, not by the run. `managementCas()` means "the live
    // CA
    // of the cluster THIS RUN belongs to" — correct while the subject IS that cluster, and wrong
    // the
    // moment a parent renders a CHILD's branch, where the subject is someone else. So the
    // SUBJECT-specific material wins and the run-scoped one is only the fallback.
    //
    // Measured 2026-09-30 on a live cold start: `manifests/nikopol-mgmt` carried
    // `CN=rke2-server-ca@…205`, which is BIOSKOP's own CA, while nikopol's apiserver runs `@…207` —
    // the CA bioskop had correctly sealed FOR nikopol and rendered into its own namespace from
    // `workloadCas()`. So BYO-CA delivery worked; only the CHILD's branch carried the parent's CA.
    // nikopol's CAPI signed a kubeconfig from it and its own apiserver answered 401 (`the server
    // has
    // asked for the client to provide credentials`) in 3ms — which is what proved the VIP and TLS
    // were never the problem.
    //
    // ★ Fifth instance of the viewpoint family (see the seeding spec § viewpoint-family),
    // compounded
    // with the scaffold duplication: ONE Secret name rendered by TWO units from different material,
    // and the copy Flux applies is the child's. Preferring the subject's makes the two agree by
    // construction rather than by coincidence.
    // ⚠️ MATERIAL follows the ADOPTER. The subject's own intention above is rendered on EVERY plane
    // (the view is uniform), but its credentials belong only to the plane that adopts it — which,
    // for
    // a cluster's own branch, is itself ONLY when it is the root. A sub-plane carrying its own
    // admin
    // material is what let it sign a kubeconfig its own apiserver then refused.
    if (!ManifestSynthesisContext.current().adopts(cluster)) {
      return;
    }
    final Optional<WorkloadClusterCasMaterial.Entry> subjectCas =
        ManifestSynthesisContext.current().workloadCas().flatMap(all -> all.forCluster(cluster));
    final Optional<ManagementClusterCaMaterial> ownCas =
        ManifestSynthesisContext.current().managementCas();
    final Optional<IncusIdentityMaterial> identity =
        ManifestSynthesisContext.current().incusIdentity();
    if (subjectCas.isPresent()) {
      final WorkloadClusterCasMaterial.Entry ca = subjectCas.orElseThrow();
      renderer.caSecrets(
          scope,
          cluster,
          namespace,
          ca.serverCa(),
          ca.clientCa(),
          ca.etcdServerCa(),
          ca.etcdPeerCa(),
          packageProfile,
          namespaceObject);
    } else {
      ownCas.ifPresent(
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
  }
}
