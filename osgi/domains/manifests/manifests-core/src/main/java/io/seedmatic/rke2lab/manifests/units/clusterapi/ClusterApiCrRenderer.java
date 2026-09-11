package io.seedmatic.rke2lab.manifests.units.clusterapi;

import io.seedmatic.rke2lab.manifests.contract.profiles.IncusIdentityMaterial;
import io.seedmatic.rke2lab.manifests.contract.profiles.WorkloadClusterCasMaterial.Pair;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

/**
 * The shared Cluster API CR-rendering behaviour both {@link ClusterApiWorkloadManifestsUnit} (the
 * greenfield workload create) and {@link ClusterApiManagementManifestsUnit} (the mgmt-cluster
 * adoption mirror) delegate to — a COLLABORATOR provided at construction, not a static helper: the
 * two units are not in an {@code is-a} relation (a workload unit is not a management unit), they
 * SHARE the behaviour of building the common CAPI/CAPN/CAPRKE2 objects. Each unit owns its own
 * {@link PackageMetadataProfile} (the packaging identity carries the unit's output dir), so every
 * method takes the profile in — the renderer holds no per-unit state.
 *
 * <p>It builds the pieces the two flows have in common: the target {@link #namespace}, the {@link
 * #lxcCluster} (kube-vip mode + per-remote CAPN identity secretRef), the {@link
 * #lxcMachineTemplate}, the {@link #rke2ControlPlane} (replica count parameterised — 3 for a
 * workload HA plane, 1 for the mgmt single control node), the four CAPRKE2 {@link #caSecrets}
 * (generic pairs, so a workload {@code Entry} or the mgmt CA set both feed it), and the CAPN {@link
 * #identitySecret}. The flow-specific objects — the workload's {@code MachineDeployment}, the
 * mgmt's concrete adopted {@code Machine}/{@code LXCMachine} — stay in their own units.
 */
public final class ClusterApiCrRenderer {

  /** The container runtime keys RKE2-in-LXC needs — mirrors {@code InstanceGrow.createInstance}. */
  private static final String INSTANCE_PROFILE = "rke2lab";

  public ApiObject namespace(
      final Construct scope,
      final String cluster,
      final String namespace,
      final PackageMetadataProfile profile) {
    return new ApiObject(
        scope,
        "namespace-" + cluster,
        ApiObjectProps.builder()
            .apiVersion("v1")
            .kind("Namespace")
            .metadata(
                ApiObjectMetadata.builder()
                    .name(namespace)
                    .annotations(profile.packageAnnotations("|Namespace||" + namespace))
                    .build())
            .build());
  }

  public ApiObject lxcCluster(
      final Construct scope,
      final String cluster,
      final String namespace,
      final String vip,
      final int apiserverPort,
      final String identitySecret,
      final PackageMetadataProfile profile,
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
                            profile.packageAnnotations(
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
                // Per-remote CAPN identity — resolved in this namespace by name; the Secret carries
                // `project: rke2lab` (single project, all clusters).
                "secretRef",
                Map.of("name", identitySecret),
                "controlPlaneEndpoint",
                Map.of("host", vip, "port", apiserverPort),
                // kube-vip mode: CAPN provisions no LB of its own; the RKE2ControlPlane bootstrap
                // deploys kube-vip fronting the VIP.
                "loadBalancer",
                Map.of("kubeVIP", Map.of()))));
    return lxcCluster;
  }

  public ApiObject lxcMachineTemplate(
      final Construct scope,
      final String cluster,
      final String namespace,
      final String role,
      final String imageFingerprint,
      final PackageMetadataProfile profile,
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
                            profile.packageAnnotations(
                                "infrastructure.cluster.x-k8s.io|LXCMachineTemplate|"
                                    + namespace
                                    + "|"
                                    + name))
                        .build())
                .build());
    template.addDependency(namespaceObject);
    template.addJsonPatch(
        JsonPatch.add(
            "/spec", Map.of("template", Map.of("spec", lxcMachineSpec(imageFingerprint)))));
    return template;
  }

  /**
   * The {@code LXCMachine}/template {@code spec} body — a privileged LXC container pinned to OUR
   * nix-built node-base by fingerprint. Shared by the workload templates and the mgmt's concrete
   * adopted {@code LXCMachine} (which adds {@code providerID} on top; see the mgmt unit).
   */
  public Map<String, Object> lxcMachineSpec(final String imageFingerprint) {
    return Map.of(
        // The node-base is a privileged LXC container (not a VM), same as the grow.
        "instanceType",
        "container",
        "profiles",
        List.of(INSTANCE_PROFILE),
        // Pin OUR nix-built node-base by content fingerprint — the same image the management grow
        // ran on, present on the remote by fingerprint.
        "image",
        Map.of("fingerprint", imageFingerprint),
        // The container runtime keys RKE2-in-LXC needs, mirroring InstanceGrow.
        "config",
        privilegedContainerConfig());
  }

  public ApiObject rke2ControlPlane(
      final Construct scope,
      final String cluster,
      final String namespace,
      final String vip,
      final String rke2Version,
      final int replicas,
      final String kubeVipVersion,
      final ApiObject controlPlaneTemplate,
      final PackageMetadataProfile profile,
      final ApiObject namespaceObject) {
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
                            profile.packageAnnotations(
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
                replicas,
                "version",
                rke2Version,
                // airGapped: our node-base BAKES rke2 (nix, immutable /nix/store) — CAPRKE2 has no
                // skip-install mode, and even airGapped still runs `sh /opt/install.sh`, so the
                // node-base bakes an INERT /opt/install.sh (exit 0) + empty /opt/rke2-artifacts
                // (see
                // nixos/capn-airgapped.nix). The install step then no-ops onto the baked binary,
                // and
                // CAPRKE2 owns /etc/rancher/rke2/config.yaml (join token + server URL), which
                // MERGES
                // with the node-base's config.yaml.d drop-ins — no ownership fight.
                "agentConfig",
                Map.of("airGapped", true),
                // Greenfield defaults; the CNI is the node-base's baked cilium (services.rke2.cni).
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

  /**
   * The four CAPRKE2 BYO-CA Secrets CAPRKE2 looks up by name ({@code <cluster>-{ca,cca,etcd,
   * peer-etcd}}) to skip generating its own CA — type {@code cluster.x-k8s.io/secret} + the
   * cluster-name label, data {@code tls.crt}/{@code tls.key}. On the BRANCH, sops-encrypted (the
   * git sops clean filter encrypts {@code tls.key} at commit; Flux decrypts). Generic pairs, so a
   * workload {@code Entry} or the mgmt CA set both feed it. See the caprke2-byo-ca-secret-contract
   * memory.
   */
  public void caSecrets(
      final Construct scope,
      final String cluster,
      final String namespace,
      final Pair serverCa,
      final Pair clientCa,
      final Pair etcdServerCa,
      final Pair etcdPeerCa,
      final PackageMetadataProfile profile,
      final ApiObject branchNamespace) {
    caSecret(scope, cluster, namespace, "ca", serverCa, profile, branchNamespace);
    caSecret(scope, cluster, namespace, "cca", clientCa, profile, branchNamespace);
    // Naming inversion is CAPRKE2's: EtcdServerCA → suffix "etcd", EtcdCA (peer) → "peer-etcd".
    caSecret(scope, cluster, namespace, "etcd", etcdServerCa, profile, branchNamespace);
    caSecret(scope, cluster, namespace, "peer-etcd", etcdPeerCa, profile, branchNamespace);
  }

  private void caSecret(
      final Construct scope,
      final String cluster,
      final String namespace,
      final String purpose,
      final Pair pair,
      final PackageMetadataProfile profile,
      final ApiObject branchNamespace) {
    final String name = cluster + "-" + purpose;
    final ApiObject secret =
        new ApiObject(
            scope,
            "secret-ca-" + name,
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Secret")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(name)
                        .namespace(namespace)
                        .labels(Map.of("cluster.x-k8s.io/cluster-name", cluster))
                        .annotations(
                            profile.packageAnnotations(
                                "|Secret|" + namespace + "|" + name, Map.of()))
                        .build())
                .build());
    secret.addDependency(branchNamespace);
    secret.addJsonPatch(JsonPatch.add("/type", "cluster.x-k8s.io/secret"));
    secret.addJsonPatch(
        JsonPatch.add(
            "/data",
            Map.of(
                "tls.crt", base64(pair.certChainPem()),
                "tls.key", base64(pair.keyPem()))));
  }

  public ApiObject identitySecret(
      final Construct scope,
      final String cluster,
      final String namespace,
      final String identitySecret,
      final IncusIdentityMaterial material,
      final String incusProject,
      final PackageMetadataProfile profile,
      final ApiObject branchNamespace) {
    final ApiObject secret =
        new ApiObject(
            scope,
            "secret-incus-identity-" + cluster,
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Secret")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(identitySecret)
                        .namespace(namespace)
                        .annotations(
                            profile.packageAnnotations(
                                "|Secret|" + namespace + "|" + identitySecret, Map.of()))
                        .build())
                .build());
    secret.addDependency(branchNamespace);
    secret.addJsonPatch(JsonPatch.add("/type", "Opaque"));
    secret.addJsonPatch(
        JsonPatch.add(
            "/data",
            Map.of(
                "server", base64(material.serverAddress()),
                "server-crt", base64(material.serverCert()),
                "client-crt", base64(material.clientCert()),
                "client-key", base64(material.clientKey()),
                // Single project (foundation 4 dropped) — the same project the node-base image
                // lives in.
                "project", base64(incusProject))));
    return secret;
  }

  private static String base64(final String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
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
        "true",
        // incus modprobes these on the host at instance start. The CAPN provider's default set
        // includes ip_tables/ip6_tables/iptable_raw — LEGACY iptables modules dropped from the
        // kernel-6.18 nixpkgs config on our nftables-only node substrate, so `modprobe ip_tables`
        // FATALs and the instance never launches. Override with the CAPN set MINUS that legacy
        // trio.
        "linux.kernel_modules",
        String.join(
            ",",
            "ip_vs",
            "ip_vs_rr",
            "ip_vs_wrr",
            "ip_vs_sh",
            "netlink_diag",
            "nf_nat",
            "overlay",
            "br_netfilter",
            "xt_socket"));
  }

  /**
   * The {@code preRKE2Command} that pulls kube-vip and writes its DaemonSet manifest into rke2's
   * server manifests dir, binding the VIP on the container's default-route interface.
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
