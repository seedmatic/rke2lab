package io.seedmatic.rke2lab.manifests.units.clusterapi;

import io.seedmatic.rke2lab.manifests.contract.profiles.IncusIdentityMaterial;
import io.seedmatic.rke2lab.manifests.contract.profiles.WorkloadClusterCasMaterial.Pair;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import org.cdk8s.JsonPatch;
import software.constructs.Construct;

/**
 * The shared Cluster API DELIVERY behaviour both {@link ClusterApiWorkloadManifestsUnit} (the
 * workload {@code ClusterProvision} recipe) and {@link ClusterApiManagementManifestsUnit} (the mgmt
 * {@code ClusterAdoption} recipe) delegate to — a COLLABORATOR provided at construction, not a
 * static helper: the two units are not in an {@code is-a} relation, they SHARE the behaviour of
 * rendering the cluster's Namespace + the credentials seed-incluster expands its CR-set from. Each
 * unit owns its own {@link PackageMetadataProfile}, so every method takes the profile in — the
 * renderer holds no per-unit state.
 *
 * <p>It builds the pieces the two recipes have in common: the target {@link #namespace}, the four
 * CAPRKE2 BYO-CA {@link #caSecrets} (generic pairs, so a workload {@code Entry} or the mgmt CA set
 * both feed it), and the CAPN {@link #identitySecret}. The CAPI/CAPN/CAPRKE2 CR-set itself
 * (Cluster/LXCCluster/RKE2ControlPlane + the owned Machines) is no longer rendered here: the
 * in-cluster {@code seed-incluster} controller materialises it from the {@code
 * ClusterProvision}/{@code ClusterAdoption} recipe (the ownerRef UID + adopt-vs-provision decision
 * are in-cluster facts GitOps cannot pre-set).
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

  private static String base64(final String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }
}
