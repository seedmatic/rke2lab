package io.seedmatic.rke2lab.manifests.contract.profiles;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The mittwald-replicator SOURCE secrets published to synth-time layers via {@code
 * ManifestSynthesisContext} — the credentials the {@code replicator-secrets} seal rehydrates from
 * {@code .secrets} host-side ({@code tekton.git}, {@code tekton.docker}, {@code tailscale.oauth})
 * and files SEALED in the cellar, revealed in-container by the manifests scion. MANIFESTS OWNS this
 * record: the seal that files it lives in manifests-bdd, so it mirrors nothing. It used to call
 * itself "the manifests-side MIRROR" and "the exact twin" of the GitHub App material's treatment —
 * false, and the five records that WERE mirrors of another domain's were deleted on 2026-10-10.
 *
 * <p>Each {@link SourceSecret} is rendered by {@code ReplicatorManifestsUnit} into {@link
 * SourceSecret#namespace()} on the node-bootstrap lane, annotated so the mittwald replicator fans
 * it out to the target namespaces the {@code replicate-from} placeholders live in. Absence — no
 * replicator secrets sealed (a bare survey / before the seal filed) — is carried as an empty {@code
 * Optional<ReplicatorSourceSecretsMaterial>} on the context, never a placeholder.
 */
public record ReplicatorSourceSecretsMaterial(List<SourceSecret> sources) {

  public ReplicatorSourceSecretsMaterial {
    sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
  }

  /**
   * One replicator SOURCE secret: {@code name} in {@code namespace} ({@code
   * kubernetes.sourceNamespace}), its Kubernetes {@code type}, its {@code stringData} entries, and
   * the {@code replicationAllowedNamespaces} the mittwald replicator may fan it out to (from {@code
   * .secrets} {@code replicateTo}).
   */
  public record SourceSecret(
      String name,
      String namespace,
      String type,
      Map<String, String> stringData,
      List<String> replicationAllowedNamespaces) {

    public SourceSecret {
      name = Objects.requireNonNull(name, "name");
      namespace = Objects.requireNonNull(namespace, "namespace");
      type = Objects.requireNonNull(type, "type");
      stringData = Map.copyOf(Objects.requireNonNull(stringData, "stringData"));
      replicationAllowedNamespaces =
          List.copyOf(
              Objects.requireNonNull(replicationAllowedNamespaces, "replicationAllowedNamespaces"));
    }
  }
}
