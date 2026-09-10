package io.seedmatic.rke2lab.seed.broker.port;

/**
 * Where a sealed value is CONSUMED — a producer-declared facet on the {@link Trail}, ORTHOGONAL to
 * the breadcrumbs (which say where a value CAME FROM; {@code reach} says where it must GO).
 * Declared at the seal by the scion that harvests it — the same "re-declare where born" discipline
 * as {@link Sensitivity}: only the producer knows, at design time, that a value must be readable by
 * the in-cluster render. It rides on the {@link Trail} (the CLEAR metadata carrier), so a consumer
 * filters SEALED entries by reach WITHOUT the passphrase (§ fil-d-ariane, § in-cluster-cellar-asset
 * in {@code seed-broker-spec}).
 */
public enum Reach {
  /**
   * The fail-closed default — consumed operator-side, or delivered node-side (the {@code
   * NODE_BOOTSTRAP} lane). Never extracted to the branch: an unmarked secret cannot leak there.
   */
  OPERATOR_ONLY,

  /**
   * Must be readable by the IN-CLUSTER manifests render — the render extracts the marked (still
   * SEALED) envelopes into the sops-encrypted branch asset, and the in-cluster read-only cellar
   * reads them back so the reveals resolve them instead of Flux pruning the branch secrets.
   */
  IN_CLUSTER
}
