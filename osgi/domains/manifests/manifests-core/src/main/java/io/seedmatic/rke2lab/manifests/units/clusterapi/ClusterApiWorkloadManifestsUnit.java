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
 * Renders the Cluster API CR set that greenfield-creates each WORKLOAD cluster, onto the MANAGEMENT
 * cluster's own branch ({@code manifests/<host>-mgmt}) — model B: the CRs live where CAPI runs, so
 * the management cluster's Flux applies them and CAPI/CAPN/CAPRKE2 reconcile a DIFFERENT cluster
 * ({@code <host>-wrkld}). There is no imperative {@code kubectl apply} and no {@code -wrkld}-branch
 * CRs (that branch carries only the workload's own app stack).
 *
 * <p>The render subject stays {@link ManifestSynthesisContext#bootstrapIdentity()} (the management
 * cluster); the workload clusters ride beside it as {@link
 * ManifestSynthesisContext#workloadTargets()} (the manifests-facet sub-facet). For each target this
 * unit derives that cluster's whole {@link ClusterNetworkBlueprint} from its {@link
 * WorkloadTarget#clusterName()} — pod/service CIDRs, the kube-vip VIP — and pins the machine image
 * to {@link ImageState#imageFingerprint()} and the RKE2 version to {@link
 * ImageState#rke2Version()}, both the node-base identity the incus scion forwarded (foundation 1b).
 * So the workload boots the SAME nix-built node-base the management cluster grew on.
 *
 * <p>The common CAPI objects (namespace, LXCCluster, LXCMachineTemplate, RKE2ControlPlane, the four
 * BYO-CA Secrets, the CAPN identity Secret) are built by the shared {@link ClusterApiCrRenderer}
 * this unit is handed at construction and delegates to — the SAME collaborator {@link
 * ClusterApiManagementManifestsUnit} uses. Only the workload-specific objects live here: the {@code
 * Cluster} (rendered {@code spec.paused: true}, a TRANSITIONAL FREEZE — see below) and the worker
 * {@code MachineDeployment} + its {@code RKE2ConfigTemplate}.
 *
 * <p>The Cluster is currently rendered {@code spec.paused: true} — a TRANSITIONAL FREEZE. CAPN
 * control-plane instances are CAPI-random-named and their linkage lives only in the management
 * etcd, so a management cold-start re-provisions a fresh set and orphans the running one (a growing
 * leak in the one {@code rke2lab} Incus project). Pausing stops CAPN reconciling the Cluster → no
 * re-provision → the leak is capped, until the workload grow is revisited on the ADOPTION model
 * (owned {@code Machine}/{@code LXCMachine} + {@code providerID} → the existing instances; see
 * {@code management-workload-topology.adoc} {@code [[mgmt-adoption]]}). {@code spec.paused} is the
 * DECLARATIVE input we author; CAPI propagates the {@code cluster.x-k8s.io/paused} annotation onto
 * owned resources (separation of roles). This reverses the earlier no-paused stance, whose two
 * Flux-incompatibility concerns are HANDLED here: (1) a paused Cluster never goes Ready → this cell
 * already renders {@code wait: false} (CAPI is long+async; see {@code
 * FluxServiceKustomizationPlanner}), so no {@code wait: true} wedge; (2) a paused Cluster can't be
 * deleted (the finalizer never clears → prune deadlocks the namespace {@code Terminating}), so we
 * must NOT prune it while paused — un-pause first when the adoption grow lands. Underlying trigger
 * is still presence in {@code workloadTargets}. The worker {@code MachineDeployment} carries {@code
 * replicas: 0} (workers are a later 2.C sub-phase — a pure replica bump). The control plane is
 * {@code master + peer1 + peer2} = 3 etcd members (peer3 is dropped for workloads; a workload is
 * NOT the full CANONICAL 4-server topology).
 *
 * <p>No-op when there are no targets (a mgmt-only / standalone run) or when no {@link ImageState}
 * is bound (a secret-blind in-cluster render / a bare survey): without the image fingerprint the
 * CRs would pin a non-existent image, so — like {@link ImageStateConfigMapManifestsUnit} — the unit
 * renders nothing rather than a misleading placeholder.
 *
 * <p>The per-remote CAPN identity Secret {@code <host>-incus-identity} the {@code
 * LXCCluster.secretRef} names (foundation 5) and the four CAPRKE2 BYO-CA Secrets are rendered HERE
 * ON THE BRANCH, sops-encrypted, when their material is revealed (a secret-full render). They are
 * NOT on {@code NODE_BOOTSTRAP} — Flux does not need them to reconcile, so they ride the branch
 * like the rest. One {@code rke2lab} incus project (foundation 4 dropped — instance names are
 * globally unique via the blueprint), so the Secret carries {@code project: rke2lab}.
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

    final ApiObject namespaceObject = renderer.namespace(scope, cluster, namespace, packageProfile);
    final ApiObject lxcCluster =
        renderer.lxcCluster(
            scope,
            cluster,
            namespace,
            vip,
            APISERVER_PORT,
            identitySecret,
            packageProfile,
            namespaceObject);
    final ApiObject controlPlaneTemplate =
        renderer.lxcMachineTemplate(
            scope,
            cluster,
            namespace,
            "control-plane",
            image.imageFingerprint(),
            packageProfile,
            namespaceObject);
    final ApiObject controlPlane =
        renderer.rke2ControlPlane(
            scope,
            cluster,
            namespace,
            vip,
            rke2Version,
            WORKLOAD_CONTROL_PLANE_REPLICAS,
            kubeVipVersion,
            controlPlaneTemplate,
            packageProfile,
            namespaceObject);
    createCluster(scope, cluster, namespace, vip, blueprint, controlPlane, lxcCluster);

    // Worker set — authored dormant (MachineDeployment replicas 0); 2.C bumps it. Its own image +
    // bootstrap templates so the bump needs no new CRs.
    final ApiObject workerTemplate =
        renderer.lxcMachineTemplate(
            scope,
            cluster,
            namespace,
            "worker",
            image.imageFingerprint(),
            packageProfile,
            namespaceObject);
    final ApiObject configTemplate =
        createRke2ConfigTemplate(scope, cluster, namespace, namespaceObject);
    createWorkerMachineDeployment(
        scope, cluster, namespace, rke2Version, configTemplate, workerTemplate, namespaceObject);

    // The CREDENTIALS this cluster needs, rendered ON THE BRANCH sops-encrypted, only when their
    // material is revealed (a secret-full render): the per-remote CAPN identity and the four
    // CAPRKE2
    // BYO-CA Secrets (so CAPRKE2 delivers OUR mammoth-skate CA to the workload node instead of
    // self-generating). A secret-blind render must not run steady-state, else it pushes them empty
    // and Flux prunes the populated ones.
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

  private ApiObject createCluster(
      final Construct scope,
      final String cluster,
      final String namespace,
      final String vip,
      final ClusterNetworkBlueprint blueprint,
      final ApiObject controlPlane,
      final ApiObject lxcCluster) {
    final ApiObject clusterObject =
        new ApiObject(
            scope,
            "cluster-" + cluster,
            ApiObjectProps.builder()
                .apiVersion("cluster.x-k8s.io/v1beta2")
                .kind("Cluster")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(cluster)
                        .namespace(namespace)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "cluster.x-k8s.io|Cluster|" + namespace + "|" + cluster))
                        .build())
                .build());
    clusterObject.addDependency(controlPlane);
    clusterObject.addDependency(lxcCluster);
    clusterObject.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                // FREEZE (transitional): paused stops CAPN reconciling this Cluster, so a
                // management
                // cold-start no longer re-provisions a fresh (random-named) control-plane set and
                // orphans the running one — capping the leak until the workload grow is revisited
                // on
                // the ADOPTION model (owned Machine/LXCMachine + providerID → existing instances;
                // see
                // management-workload-topology.adoc [[mgmt-adoption]]). spec.paused is the
                // DECLARATIVE
                // input we author; CAPI propagates the cluster.x-k8s.io/paused annotation onto
                // owned
                // resources (separation of roles). Safe here: the Cluster cell renders wait:false
                // (FluxServiceKustomizationPlanner), so a never-Ready paused Cluster does not wedge
                // its Flux Kustomization; and we must NOT prune it while paused (the finalizer
                // would
                // never clear → namespace Terminating) — unpause first when the adoption grow
                // lands.
                "paused",
                true,
                "clusterNetwork",
                Map.of(
                    "pods",
                    Map.of("cidrBlocks", List.of(blueprint.podCidr())),
                    "services",
                    Map.of("cidrBlocks", List.of(blueprint.serviceCidr())),
                    "serviceDomain",
                    "cluster.local"),
                "controlPlaneEndpoint",
                Map.of("host", vip, "port", APISERVER_PORT),
                "controlPlaneRef",
                Map.of(
                    "apiGroup",
                    "controlplane.cluster.x-k8s.io",
                    "kind",
                    "RKE2ControlPlane",
                    "name",
                    cluster + "-control-plane"),
                "infrastructureRef",
                Map.of(
                    "apiGroup",
                    "infrastructure.cluster.x-k8s.io",
                    "kind",
                    "LXCCluster",
                    "name",
                    cluster))));
    return clusterObject;
  }

  private ApiObject createWorkerMachineDeployment(
      final Construct scope,
      final String cluster,
      final String namespace,
      final String rke2Version,
      final ApiObject configTemplate,
      final ApiObject workerTemplate,
      final ApiObject namespaceObject) {
    final String name = cluster + "-md-0";
    final ApiObject deployment =
        new ApiObject(
            scope,
            "machinedeployment-" + name,
            ApiObjectProps.builder()
                .apiVersion("cluster.x-k8s.io/v1beta2")
                .kind("MachineDeployment")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(name)
                        .namespace(namespace)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "cluster.x-k8s.io|MachineDeployment|" + namespace + "|" + name))
                        .build())
                .build());
    deployment.addDependency(configTemplate);
    deployment.addDependency(workerTemplate);
    deployment.addDependency(namespaceObject);
    deployment.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "clusterName",
                cluster,
                // Dormant: workers are a later 2.C sub-phase (a pure replica bump).
                "replicas",
                0,
                "selector",
                Map.of("matchLabels", Map.of("cluster.x-k8s.io/cluster-name", cluster)),
                "template",
                Map.of(
                    "spec",
                    Map.of(
                        "version",
                        rke2Version,
                        "clusterName",
                        cluster,
                        "bootstrap",
                        Map.of(
                            "configRef",
                            Map.of(
                                "apiGroup",
                                "bootstrap.cluster.x-k8s.io",
                                "kind",
                                "RKE2ConfigTemplate",
                                "name",
                                cluster + "-agent")),
                        "infrastructureRef",
                        Map.of(
                            "apiGroup",
                            "infrastructure.cluster.x-k8s.io",
                            "kind",
                            "LXCMachineTemplate",
                            "name",
                            cluster + "-worker"))))));
    return deployment;
  }

  private ApiObject createRke2ConfigTemplate(
      final Construct scope,
      final String cluster,
      final String namespace,
      final ApiObject namespaceObject) {
    final String name = cluster + "-agent";
    final ApiObject configTemplate =
        new ApiObject(
            scope,
            "rke2configtemplate-" + name,
            ApiObjectProps.builder()
                .apiVersion("bootstrap.cluster.x-k8s.io/v1beta2")
                .kind("RKE2ConfigTemplate")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(name)
                        .namespace(namespace)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "bootstrap.cluster.x-k8s.io|RKE2ConfigTemplate|"
                                    + namespace
                                    + "|"
                                    + name))
                        .build())
                .build());
    configTemplate.addDependency(namespaceObject);
    // airGapped like the control plane: the worker node-base bakes rke2 too, so CAPRKE2's install
    // no-ops onto the baked binary (inert /opt/install.sh). Workers are replicas:0 today; the
    // role=server-vs-agent split of the homogeneous image (rke2.nix bakes role=server) is the
    // remaining reconciliation before workers are enabled (phase 2.C).
    configTemplate.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of("template", Map.of("spec", Map.of("agentConfig", Map.of("airGapped", true))))));
    return configTemplate;
  }
}
