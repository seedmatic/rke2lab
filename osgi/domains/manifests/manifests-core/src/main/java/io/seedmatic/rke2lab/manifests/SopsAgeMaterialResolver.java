package io.seedmatic.rke2lab.manifests;

import io.seedmatic.rke2lab.manifests.contract.profiles.SopsAgeMaterial;
import io.seedmatic.rke2lab.ndh.contract.ClusterAgeIdentity;
import io.seedmatic.rke2lab.seed.broker.port.EnclosureGate;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The synthesis service's pre-synthesis step: resolve the cluster's age identity BEFORE the unit
 * loop runs, so the unit only renders. It reads it through {@link ClusterAgeIdentity}, the ndh
 * domain's door to the identity the operator handed over from the keyring (passed in, never reached
 * statically) — the same door cluster-pki seals the node bundle with.
 *
 * <p>Fail-soft on absence, fail-fast on malformation: no identity handed over ⟹ {@link
 * Optional#empty()} (ephemeral / test runs, where the unit then skips). A present-but-malformed one
 * raises inside the reader — a defect to surface, not a silent skip.
 */
final class SopsAgeMaterialResolver {

  private static final Logger LOG = LoggerFactory.getLogger(SopsAgeMaterialResolver.class);

  private final ClusterAgeIdentity clusterAge;
  private final Optional<EnclosureGate> enclosure;

  SopsAgeMaterialResolver(ClusterAgeIdentity clusterAge, Optional<EnclosureGate> enclosure) {
    this.clusterAge = clusterAge;
    this.enclosure = enclosure;
  }

  Optional<SopsAgeMaterial> resolve() {
    // IN_CLUSTER the sops-age Secret is a NODE_BOOTSTRAP artifact already applied at the operator's
    // grow — a render never re-emits it, and the identity is never read there.
    if (enclosure.map(EnclosureGate::inCluster).orElse(false)) {
      LOG.debug("In-cluster render — sops-age Secret is a bootstrap artifact, not re-emitted");
      return Optional.empty();
    }
    if (!clusterAge.present()) {
      LOG.debug("No cluster age identity handed over — sops-age Secret will be skipped");
      return Optional.empty();
    }
    return Optional.of(new SopsAgeMaterial(clusterAge.identity()));
  }
}
