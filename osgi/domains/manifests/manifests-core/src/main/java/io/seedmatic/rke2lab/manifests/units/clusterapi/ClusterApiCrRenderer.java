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
 * The shared Cluster API DELIVERY behaviour both {@link ClusterApiWorkloadManifestsUnit} and {@link
 * ClusterApiManagementManifestsUnit} delegate to — a COLLABORATOR provided at construction, not a
 * static helper: the two units are not in an {@code is-a} relation, they SHARE the behaviour of
 * rendering a cluster's 2×2 intent + the credentials seed-incluster expands its CR-set from. Each
 * unit owns its own {@link PackageMetadataProfile}, so every method takes the profile in — the
 * renderer holds no per-unit state.
 *
 * <p>It builds the pieces the two recipes have in common: the target {@link #namespace}, the
 * cluster-level {@link #clusterIntention} + the control-node {@link #controlNodePoolIntention}
 * (identical intent for mgmt and workload — only kind, pet count and remote endpoint differ), the
 * four CAPRKE2 BYO-CA {@link #caSecrets} (generic pairs, so a workload {@code Entry} or the mgmt CA
 * set both feed it), and the CAPN {@link #identitySecret}. The CAPI/CAPN/CAPRKE2 CR-set itself
 * (Cluster/LXCCluster/RKE2ControlPlane + the owned Machines) is NOT rendered here: the in-cluster
 * {@code seed-incluster} controller materialises it from the {@code ClusterIntention} + {@code
 * PoolIntention} intent (the ownerRef UID + adopt-vs-provision decision are in-cluster facts GitOps
 * cannot pre-set).
 */
public final class ClusterApiCrRenderer {

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

  /**
   * The pool identity of the control-plane pool — its CAPI treatment derives from role, not this.
   */
  private static final String CONTROL_NODE_POOL = "control-node";

  /**
   * The cluster-level {@code ClusterIntention} — the Flux-owned intent seed-incluster reconciles
   * adopt-first into the Cluster + LXCCluster. Cluster-scoped facts only (VIP, CIDRs, remote +
   * identity, federated kind); the per-pool roster + template live in the {@code PoolIntention}
   * children built by {@link #controlNodePoolIntention}. {@code remoteEndpoint} may be empty (the
   * local engine, resolved from the identity Secret's {@code server} — the management case).
   */
  public ApiObject clusterIntention(
      final Construct scope,
      final String cluster,
      final String namespace,
      final String kind,
      final String vip,
      final int port,
      final List<String> podCidrs,
      final List<String> serviceCidrs,
      final String remoteEndpoint,
      final String identitySecret,
      final PackageMetadataProfile profile,
      final ApiObject branchNamespace) {
    final ApiObject intention =
        new ApiObject(
            scope,
            "clusterintention-" + cluster,
            ApiObjectProps.builder()
                .apiVersion("cluster.seedmatic.io/v1alpha1")
                .kind("ClusterIntention")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(cluster)
                        .namespace(namespace)
                        .annotations(
                            profile.packageAnnotations(
                                "cluster.seedmatic.io|ClusterIntention|"
                                    + namespace
                                    + "|"
                                    + cluster))
                        .build())
                .build());
    intention.addDependency(branchNamespace);
    intention.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "clusterName",
                cluster,
                "namespace",
                namespace,
                "kind",
                kind,
                "controlPlaneEndpoint",
                Map.of("host", vip, "port", port),
                "clusterNetwork",
                Map.of(
                    "podCIDRs", podCidrs,
                    "serviceCIDRs", serviceCidrs,
                    "serviceDomain", "cluster.local"),
                "remote",
                Map.of("endpoint", remoteEndpoint, "identitySecretName", identitySecret))));
    return intention;
  }

  /**
   * The control-node {@code PoolIntention} — the pool-level intent seed-incluster reconciles into
   * the RKE2ControlPlane + LXCMachineTemplate + the owned per-pet Machine/LXCMachine. {@code role:
   * control-plane} derives the CAPI treatment (etcd members, the object the Cluster references).
   * Its pets are the deterministic control-plane names ({@code <cluster>-master[, -peer1,
   * -peer2]}). No ownerRef to the {@code ClusterIntention}: both ride the branch and are
   * Flux-pruned; the CAPI CR-set under the PoolAdoption is the ownerRef-GC chain. Worker pools are
   * a follow-up (not emitted yet — the coding scope is control-node only).
   */
  public ApiObject controlNodePoolIntention(
      final Construct scope,
      final String cluster,
      final String namespace,
      final String vip,
      final int port,
      final String rke2Version,
      final String imageFingerprint,
      final List<String> petNames,
      final PackageMetadataProfile profile,
      final ApiObject clusterIntention) {
    final String poolName = cluster + "-" + CONTROL_NODE_POOL;
    final List<Object> nodes =
        petNames.stream().map(name -> (Object) Map.of("name", name)).toList();
    final ApiObject poolIntention =
        new ApiObject(
            scope,
            "poolintention-" + poolName,
            ApiObjectProps.builder()
                .apiVersion("cluster.seedmatic.io/v1alpha1")
                .kind("PoolIntention")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(poolName)
                        .namespace(namespace)
                        .annotations(
                            profile.packageAnnotations(
                                "cluster.seedmatic.io|PoolIntention|" + namespace + "|" + poolName))
                        .build())
                .build());
    poolIntention.addDependency(clusterIntention);
    poolIntention.addJsonPatch(
        JsonPatch.add(
            "/spec",
            Map.of(
                "clusterRef", cluster,
                "namespace", namespace,
                "pool", CONTROL_NODE_POOL,
                "role", "control-plane",
                "image", Map.of("fingerprint", imageFingerprint),
                "rke2Version", rke2Version,
                "controlPlaneEndpoint", Map.of("host", vip, "port", port),
                "nodes", nodes)));
    return poolIntention;
  }

  private static String base64(final String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }
}
