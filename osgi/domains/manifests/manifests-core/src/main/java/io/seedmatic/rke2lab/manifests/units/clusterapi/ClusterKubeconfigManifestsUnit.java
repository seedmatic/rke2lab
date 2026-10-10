package io.seedmatic.rke2lab.manifests.units.clusterapi;

import io.seedmatic.rke2lab.clusterpki.contract.AdminCredentials;
import io.seedmatic.rke2lab.clusterpki.contract.OperatorKubeconfig;
import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestAnnotation;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.contract.profiles.BootstrapIdentity;
import io.seedmatic.rke2lab.manifests.contract.profiles.NetworkTopology;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

/**
 * Renders the admin kubeconfig two ways from one {@link AdminCredentials}, discriminated by the
 * {@code config.kubernetes.io/local-config} annotation — no cdk8s extension, no host-side render
 * outside the synthesis pipeline:
 *
 * <ul>
 *   <li><b>The operator kubeconfig</b> — endpoint = the node's deterministic mDNS name ({@code
 *       <cluster>-<node>.local}), the endpoint seed-outcluster reaches the master over. Carried in
 *       a {@code local-config} Secret so the exploder lands it as a hidden dotfile RKE2 never
 *       applies; the manifests scion reads it host-side and writes it to {@code kubeconfigRef} (the
 *       readiness probe's kubeconfig). The local-config twin of the incus NoCloud cloud-config
 *       seed.
 *   <li><b>The CAPI kubeconfig Secret</b> — endpoint = the kube-vip VIP, the stable cluster
 *       endpoint new nodes join over. The canonical Cluster API {@code <cluster>-kubeconfig} Secret
 *       (data key {@code value}, type {@code cluster.x-k8s.io/secret}, {@code
 *       cluster.x-k8s.io/cluster-name} label) that CAPI reads in-cluster to seed the OTHER nodes
 *       (further control-plane + workers); seed-outcluster itself only bootstraps the master
 *       control node. A real credential, so it rides the NODE_BOOTSTRAP lane (with its namespace) —
 *       seeded node-side over devlxd at the grow, NEVER on the reconciled branch: a secret-blind
 *       in-cluster render early-returns here (no material), which would otherwise STRIP a
 *       branch-rendered Secret and leave CAPI without its kubeconfig. The operator-kubeconfig above
 *       needs no such lane — it is consumed host-side at the grow, never a cluster resource.
 * </ul>
 *
 * <p>The unit only RENDERS. The manifests scion reveals the cluster-pki {@link AdminCredentials}
 * from the cellar in-container and hands the record to the synthesis request as it is; the unit
 * renders it through {@link OperatorKubeconfig}, the one kubeconfig renderer. Absent material (a
 * bare survey) or an unknown cluster → the unit renders nothing.
 */
public final class ClusterKubeconfigManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.CLUSTER_API + "/kubeconfig";

  private static final String APISERVER_PORT = "6443";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile(ManifestDomainCatalog.CLUSTER_API, "kubeconfig");

  public ClusterKubeconfigManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of());
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    final ManifestSynthesisContext synth = ManifestSynthesisContext.current();
    final String clusterName = synth.bootstrapIdentity().clusterName();
    final Optional<AdminCredentials> maybeMaterial = synth.operatorPki();

    if (BootstrapIdentity.UNKNOWN.equals(clusterName) || maybeMaterial.isEmpty()) {
      return;
    }
    final AdminCredentials material = maybeMaterial.orElseThrow();
    final String nodeName = synth.bootstrapIdentity().nodeName();
    final NetworkTopology topology = synth.networkTopology();

    // One namespace per managed cluster, WE create it (not CAPI) — so it carries the rke2lab prefix
    // like every other rke2lab-owned namespace (rke2lab-system, rke2lab-secrets): {@code
    // rke2lab-<cluster>}. The Cluster CR + its Machines + this kubeconfig Secret co-locate here,
    // and
    // CAPI reads the canonical {@code <cluster>-kubeconfig} Secret WITHIN it (the secret name stays
    // the bare cluster name — CAPI resolves it by name inside the namespace). The Namespace rides
    // the NODE_BOOTSTRAP lane WITH the CAPI Secret (the Secret dependsOn it, so the bootstrap file
    // lists the Namespace first), a self-contained set applied node-side before Flux.
    final String clusterNamespace = "rke2lab-" + clusterName;
    final ApiObject namespace = createClusterNamespace(scope, clusterNamespace);

    renderOperatorKubeconfig(
        scope, material, clusterName, clusterNamespace, synth.bootstrapIdentity().nodeFabricFqdn());
    renderCapiKubeconfigSecret(
        scope, material, clusterName, clusterNamespace, topology.vipHostInetAddr(), namespace);
  }

  private ApiObject createClusterNamespace(final Construct scope, final String namespace) {
    return new ApiObject(
        scope,
        "namespace-" + namespace,
        ApiObjectProps.builder()
            .apiVersion("v1")
            .kind("Namespace")
            .metadata(
                ApiObjectMetadata.builder()
                    .name(namespace)
                    .annotations(
                        packageProfile.packageAnnotations(
                            Map.of(ManifestAnnotation.NODE_BOOTSTRAP.key(), "true")))
                    .build())
            .build());
  }

  // The operator kubeconfig over the node's FABRIC name, host-consumed: a local-config Secret the
  // exploder hides as a dotfile (never applied); the scion reads it host-side into kubeconfigRef.
  //
  // This is the FIRST-CONTACT context — the one an operator uses before any cluster answers, which
  // is why it targets a node rather than the VIP (the CAPI Secret above takes the VIP, and from a
  // standing management cluster every other cluster is reached by ITS VIP and its nodes read off
  // its
  // own API). The name arrives already-derived rather than re-concatenated here: it is the
  // NamePlan's
  // single source, and it used to be an mDNS `.local` name that only a same-L2 asker could resolve.
  private void renderOperatorKubeconfig(
      final Construct scope,
      final AdminCredentials material,
      final String clusterName,
      final String namespace,
      final String nodeFabricFqdn) {
    final String name = clusterName + "-operator-kubeconfig";
    final String server = "https://" + nodeFabricFqdn + ":" + APISERVER_PORT;

    final ApiObject secret =
        new ApiObject(
            scope,
            "secret-operator-kubeconfig",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Secret")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(name)
                        .namespace(namespace)
                        .annotations(
                            packageProfile.packageAnnotations(
                                Map.of(ManifestAnnotation.LOCAL_CONFIG.key(), "true")))
                        .build())
                .build());

    secret.addJsonPatch(JsonPatch.add("/type", "Opaque"));
    secret.addJsonPatch(
        JsonPatch.add(
            "/data", Map.of("kubeconfig.yaml", base64(kubeconfig(material, clusterName, server)))));
  }

  // The canonical CAPI <cluster>-kubeconfig Secret over the VIP: CAPI reads it in-cluster to seed
  // the further nodes. Data key `value`, type + cluster-name label per the CAPI contract. A real
  // credential → the NODE_BOOTSTRAP lane (seeded node-side over devlxd at the grow), so a
  // secret-blind in-cluster render — which early-returns here for lack of material — cannot strip
  // it
  // off the branch. It dependsOn the namespace so the bootstrap file lists the Namespace first.
  private void renderCapiKubeconfigSecret(
      final Construct scope,
      final AdminCredentials material,
      final String clusterName,
      final String namespace,
      final String vipHostInetAddr,
      final ApiObject namespaceObject) {
    final String name = clusterName + "-kubeconfig";
    final String server = "https://" + vipHostInetAddr + ":" + APISERVER_PORT;

    final ApiObject secret =
        new ApiObject(
            scope,
            "secret-capi-kubeconfig",
            ApiObjectProps.builder()
                .apiVersion("v1")
                .kind("Secret")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(name)
                        .namespace(namespace)
                        .labels(Map.of("cluster.x-k8s.io/cluster-name", clusterName))
                        .annotations(
                            packageProfile.packageAnnotations(
                                Map.of(ManifestAnnotation.NODE_BOOTSTRAP.key(), "true")))
                        .build())
                .build());

    secret.addDependency(namespaceObject);
    secret.addJsonPatch(JsonPatch.add("/type", "cluster.x-k8s.io/secret"));
    secret.addJsonPatch(
        JsonPatch.add("/data", Map.of("value", base64(kubeconfig(material, clusterName, server)))));
  }

  /**
   * One cluster, one way in whose context bears the cluster's name — the single-endpoint case of
   * {@link OperatorKubeconfig}, the one kubeconfig renderer. Both Secrets this unit renders differ
   * only by the endpoint they dial.
   */
  private String kubeconfig(
      final AdminCredentials material, final String clusterName, final String server) {
    return new OperatorKubeconfig(
            List.of(
                new OperatorKubeconfig.ClusterAccess(
                    clusterName,
                    material,
                    List.of(new OperatorKubeconfig.Access(clusterName, server)))))
        .render();
  }

  private static String base64(final String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }
}
