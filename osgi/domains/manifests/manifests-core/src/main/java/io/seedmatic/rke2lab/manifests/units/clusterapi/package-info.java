// @codebase
/**
 * Cluster API domain manifest units: the CAPI operator and the secrets/config it needs to drive
 * Incus-backed clusters.
 *
 * <ul>
 *   <li>{@link io.seedmatic.rke2lab.manifests.units.clusterapi.ClusterApiOperatorManifestsUnit} —
 *       the Cluster API operator plus core/infrastructure/control-plane providers.
 *   <li>{@link io.seedmatic.rke2lab.manifests.units.clusterapi.ImageStateConfigMapManifestsUnit} —
 *       the Stage A → Stage B control-node image identity, surfaced as a ConfigMap.
 *   <li>{@link io.seedmatic.rke2lab.manifests.units.clusterapi.ClusterKubeconfigManifestsUnit} —
 *       the operator + CAPI kubeconfigs for the management cluster.
 *   <li>{@link io.seedmatic.rke2lab.manifests.units.clusterapi.ClusterApiWorkloadManifestsUnit} —
 *       the workload CAPI CR set (rendered on {@code -mgmt}, model B) plus the per-remote CAPN
 *       identity Secret it references (node-bootstrap lane).
 * </ul>
 *
 * <p>Registered by {@link io.seedmatic.rke2lab.manifests.domain.ClusterApiDomainRegistrar}.
 *
 * <h2>Related documentation</h2>
 *
 * <ul>
 *   <li><a href="../../../../../../../../../../docs/manifest-conditional-inclusion.adoc">Manifest
 *       Conditional Inclusion</a> — bootstrap-identity access pattern (the IncusIdentitySecret
 *       example).
 *   <li><a href="../../../../../../../../../../docs/bootstrap-identity-provider.adoc">Bootstrap
 *       Identity Provider</a> — how these units read cluster/infrastructure identity.
 *   <li><a href="../../../../../../../../../../docs/manifests-architecture.adoc">Manifests
 *       Architecture</a> — the unit model and synthesis flow.
 * </ul>
 */
@org.jspecify.annotations.NullMarked
package io.seedmatic.rke2lab.manifests.units.clusterapi;
