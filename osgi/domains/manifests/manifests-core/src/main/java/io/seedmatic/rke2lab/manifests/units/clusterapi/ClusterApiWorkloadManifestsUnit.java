package io.seedmatic.rke2lab.manifests.units.clusterapi;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.contract.WorkloadTarget;
import io.seedmatic.rke2lab.manifests.contract.profiles.ImageState;
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
 * <p>Every CR is emitted with {@code spec.paused: true} on the {@code Cluster}: the set is authored
 * now but does not reconcile until a deliberate unpause (the 2.B bring-up trigger), and the worker
 * {@code MachineDeployment} carries {@code replicas: 0} (workers are a later 2.C sub-phase — a pure
 * replica bump). The control plane is {@code master + peer1 + peer2} = 3 etcd members (peer3 is
 * dropped for workloads; a workload is NOT the full CANONICAL 4-server topology).
 *
 * <p>No-op when there are no targets (a mgmt-only / standalone run) or when no {@link ImageState}
 * is bound (a secret-blind in-cluster render / a bare survey): without the image fingerprint the
 * CRs would pin a non-existent image, so — like {@link ImageStateConfigMapManifestsUnit} — the unit
 * renders nothing rather than a misleading placeholder.
 *
 * <p>Two provisioning inputs settle in later foundations, referenced by convention here: the
 * per-cluster incus PROJECT + network/profile the {@code LXCMachineTemplate} lands on (foundation
 * 4) and the per-remote CAPN identity Secret {@code <cluster>-incus-identity} the {@code
 * LXCCluster.secretRef} names (foundation 5). The rke2 config-ownership reconciliation (CAPRKE2's
 * generated {@code config.yaml} vs the node-base's baked config/CNI) is validated when the set is
 * first unpaused, not asserted here.
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

  /**
   * The default incus profile the machine templates attach to (per-cluster profile = foundation 4).
   */
  private static final String DEFAULT_PROFILE = "default";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile(ManifestDomainCatalog.CLUSTER_API, OUTPUT_DIR);

  public ClusterApiWorkloadManifestsUnit() {
    // dependsOn the operator layer: the CAPI/CAPN/CAPRKE2 CRDs these CRs target are registered by
    // ClusterApiOperatorManifestsUnit, so this only dry-runs after that layer is healthy.
    super(MANIFEST_UNIT_ID, List.of(ClusterApiOperatorManifestsUnit.MANIFEST_UNIT_ID));
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
    // `rke2lab` incus project (instance names are already globally unique via the blueprint), so
    // the
    // Secret is keyed by the host/remote (bioskop, nikopol) and carries `project: rke2lab`.
    // Foundation
    // 5 renders a copy into each workload namespace; CAPN resolves secretRef within that namespace.
    final String identitySecret = target.host() + "-incus-identity";
    // CAPI/CAPRKE2 want the k8s version with a leading `v`; the nix-emitted rke2Version has none
    // (e.g. `1.34.8+rke2r2`) — prefix it iff absent.
    final String rke2Version =
        image.rke2Version().startsWith("v") ? image.rke2Version() : "v" + image.rke2Version();

    final ApiObject namespaceObject = createNamespace(scope, cluster, namespace);
    final ApiObject lxcCluster =
        createLxcCluster(scope, cluster, namespace, vip, identitySecret, namespaceObject);
    final ApiObject controlPlaneTemplate =
        createLxcMachineTemplate(
            scope, cluster, namespace, "control-plane", image, namespaceObject);
    final ApiObject controlPlane =
        createRke2ControlPlane(
            scope, cluster, namespace, vip, rke2Version, controlPlaneTemplate, namespaceObject);
    createCluster(scope, cluster, namespace, vip, blueprint, controlPlane, lxcCluster);

    // Worker set — authored dormant (MachineDeployment replicas 0); 2.C bumps it. Its own image +
    // bootstrap templates so the bump needs no new CRs.
    final ApiObject workerTemplate =
        createLxcMachineTemplate(scope, cluster, namespace, "worker", image, namespaceObject);
    final ApiObject configTemplate =
        createRke2ConfigTemplate(scope, cluster, namespace, namespaceObject);
    createWorkerMachineDeployment(
        scope, cluster, namespace, rke2Version, configTemplate, workerTemplate, namespaceObject);
  }

  private ApiObject createNamespace(
      final Construct scope, final String cluster, final String namespace) {
    return new ApiObject(
        scope,
        "namespace-" + cluster,
        ApiObjectProps.builder()
            .apiVersion("v1")
            .kind("Namespace")
            .metadata(
                ApiObjectMetadata.builder()
                    .name(namespace)
                    .annotations(packageProfile.packageAnnotations("|Namespace||" + namespace))
                    .build())
            .build());
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
                // Authored dormant: reconciliation begins on a deliberate unpause (2.B bring-up).
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

  private ApiObject createLxcCluster(
      final Construct scope,
      final String cluster,
      final String namespace,
      final String vip,
      final String identitySecret,
      final ApiObject namespaceObject) {
    final ApiObject lxcCluster =
        new ApiObject(
            scope,
            "lxccluster-" + cluster,
            ApiObjectProps.builder()
                .apiVersion("infrastructure.cluster.x-k8s.io/v1alpha2")
                .kind("LXCCluster")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(cluster)
                        .namespace(namespace)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "infrastructure.cluster.x-k8s.io|LXCCluster|"
                                    + namespace
                                    + "|"
                                    + cluster))
                        .build())
                .build());
    lxcCluster.addDependency(namespaceObject);
    lxcCluster.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                // Per-remote CAPN identity (foundation 5) — resolved in this namespace by name; the
                // Secret carries `project: rke2lab` (single project, all clusters).
                "secretRef",
                Map.of("name", identitySecret),
                "controlPlaneEndpoint",
                Map.of("host", vip, "port", APISERVER_PORT),
                // kube-vip mode: CAPN provisions no LB of its own; the RKE2ControlPlane bootstrap
                // deploys kube-vip fronting the VIP (below).
                "loadBalancer",
                Map.of("kubeVIP", Map.of()))));
    return lxcCluster;
  }

  private ApiObject createRke2ControlPlane(
      final Construct scope,
      final String cluster,
      final String namespace,
      final String vip,
      final String rke2Version,
      final ApiObject controlPlaneTemplate,
      final ApiObject namespaceObject) {
    final String kubeVipVersion =
        ManifestSynthesisContext.current().componentVersions().of(Component.KUBE_VIP);
    final ApiObject controlPlane =
        new ApiObject(
            scope,
            "rke2controlplane-" + cluster,
            ApiObjectProps.builder()
                .apiVersion("controlplane.cluster.x-k8s.io/v1beta2")
                .kind("RKE2ControlPlane")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(cluster + "-control-plane")
                        .namespace(namespace)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "controlplane.cluster.x-k8s.io|RKE2ControlPlane|"
                                    + namespace
                                    + "|"
                                    + cluster
                                    + "-control-plane"))
                        .build())
                .build());
    controlPlane.addDependency(controlPlaneTemplate);
    controlPlane.addDependency(namespaceObject);
    controlPlane.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "replicas",
                WORKLOAD_CONTROL_PLANE_REPLICAS,
                "version",
                rke2Version,
                // Greenfield defaults; the rke2 config ownership (CAPRKE2 config.yaml vs the
                // node-base baked config/CNI) is reconciled at first unpause, not asserted here.
                "serverConfig",
                Map.of(),
                // kube-vip fronts the control-plane endpoint: the replicas register on the VIP.
                "registrationMethod",
                "address",
                "registrationAddress",
                vip,
                // Generate the kube-vip DaemonSet manifest into rke2's server manifests dir at
                // boot.
                "preRKE2Commands",
                List.of(kubeVipBootstrapCommand(vip, kubeVipVersion)),
                "files",
                List.of(kubeVipRbacFile()),
                "machineTemplate",
                Map.of(
                    "spec",
                    Map.of(
                        "infrastructureRef",
                        Map.of(
                            "apiGroup",
                            "infrastructure.cluster.x-k8s.io",
                            "kind",
                            "LXCMachineTemplate",
                            "name",
                            cluster + "-control-plane"))),
                "rolloutStrategy",
                Map.of("type", "RollingUpdate", "rollingUpdate", Map.of("maxSurge", 1)))));
    return controlPlane;
  }

  private ApiObject createLxcMachineTemplate(
      final Construct scope,
      final String cluster,
      final String namespace,
      final String role,
      final ImageState image,
      final ApiObject namespaceObject) {
    final String name = cluster + "-" + role;
    final ApiObject template =
        new ApiObject(
            scope,
            "lxcmachinetemplate-" + name,
            ApiObjectProps.builder()
                .apiVersion("infrastructure.cluster.x-k8s.io/v1alpha2")
                .kind("LXCMachineTemplate")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(name)
                        .namespace(namespace)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "infrastructure.cluster.x-k8s.io|LXCMachineTemplate|"
                                    + namespace
                                    + "|"
                                    + name))
                        .build())
                .build());
    template.addDependency(namespaceObject);
    template.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "template",
                Map.of(
                    "spec",
                    Map.of(
                        // The node-base is a privileged LXC container (not a VM), same as the grow.
                        "instanceType",
                        "container",
                        "profiles",
                        List.of(DEFAULT_PROFILE),
                        // Pin OUR nix-built node-base by content fingerprint (foundation 1b) — the
                        // same image the management grow ran on, present on the remote by
                        // fingerprint.
                        "image",
                        Map.of("fingerprint", image.imageFingerprint()),
                        // The container runtime keys RKE2-in-LXC needs, mirroring InstanceGrow.
                        "config",
                        privilegedContainerConfig())))));
    return template;
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
    configTemplate.addJsonPatch(
        JsonPatch.add(
            "/spec", Map.of("template", Map.of("spec", Map.of("agentConfig", Map.of())))));
    return configTemplate;
  }

  /** The container runtime keys RKE2-in-LXC needs — mirrors {@code InstanceGrow.createInstance}. */
  private static Map<String, String> privilegedContainerConfig() {
    return Map.of(
        "raw.lxc",
        String.join(
            "\n",
            "lxc.mount.auto = proc:rw sys:rw cgroup:rw",
            "lxc.apparmor.profile = unconfined",
            "lxc.cap.drop ="),
        "security.privileged",
        "true",
        "security.nesting",
        "true",
        "security.syscalls.intercept.bpf",
        "true",
        "security.syscalls.intercept.bpf.devices",
        "true");
  }

  /**
   * The {@code preRKE2Command} that pulls kube-vip and writes its DaemonSet manifest into rke2's
   * server manifests dir, binding the VIP on the container's default-route interface. Mirrors the
   * CAPN/CAPRKE2 kube-vip templates, at OUR pinned kube-vip version.
   */
  private static String kubeVipBootstrapCommand(final String vip, final String kubeVipVersion) {
    final String image = "ghcr.io/kube-vip/kube-vip:" + kubeVipVersion;
    return "mkdir -p /var/lib/rancher/rke2/server/manifests/ && ctr images pull "
        + image
        + " && ctr run --rm --net-host "
        + image
        + " vip /kube-vip manifest daemonset --arp --interface "
        + "$(ip -4 -j route list default | jq -r .[0].dev) --address "
        + vip
        + " --controlplane --leaderElection --taint --services --inCluster"
        + " | tee /var/lib/rancher/rke2/server/manifests/kube-vip.yaml";
  }

  /**
   * The kube-vip RBAC (ServiceAccount + ClusterRole + binding) landed as an rke2 server manifest.
   */
  private static Map<String, Object> kubeVipRbacFile() {
    final String content =
        String.join(
            "\n",
            "apiVersion: v1",
            "kind: ServiceAccount",
            "metadata:",
            "  name: kube-vip",
            "  namespace: kube-system",
            "---",
            "apiVersion: rbac.authorization.k8s.io/v1",
            "kind: ClusterRole",
            "metadata:",
            "  annotations:",
            "    rbac.authorization.kubernetes.io/autoupdate: \"true\"",
            "  name: system:kube-vip-role",
            "rules:",
            "  - apiGroups: [\"\"]",
            "    resources: [\"services\", \"services/status\", \"nodes\", \"endpoints\"]",
            "    verbs: [\"list\",\"get\",\"watch\", \"update\"]",
            "  - apiGroups: [\"coordination.k8s.io\"]",
            "    resources: [\"leases\"]",
            "    verbs: [\"list\", \"get\", \"watch\", \"update\", \"create\"]",
            "---",
            "kind: ClusterRoleBinding",
            "apiVersion: rbac.authorization.k8s.io/v1",
            "metadata:",
            "  name: system:kube-vip-binding",
            "roleRef:",
            "  apiGroup: rbac.authorization.k8s.io",
            "  kind: ClusterRole",
            "  name: system:kube-vip-role",
            "subjects:",
            "- kind: ServiceAccount",
            "  name: kube-vip",
            "  namespace: kube-system");
    return Map.of(
        "path",
        "/var/lib/rancher/rke2/server/manifests/kube-vip-rbac.yaml",
        "owner",
        "root:root",
        "content",
        content);
  }
}
