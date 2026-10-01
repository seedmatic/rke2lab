package io.seedmatic.rke2lab.manifests;

import io.seedmatic.rke2lab.manifests.contract.SshToAgeConverter;
import io.seedmatic.rke2lab.manifests.contract.profiles.SopsAgeMaterial;
import io.seedmatic.rke2lab.ndh.contract.NdhKeystoreCatalog;
import io.seedmatic.rke2lab.ndh.contract.NdhKeystoreReader;
import io.seedmatic.rke2lab.seed.broker.port.EnclosureGate;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The synthesis service's pre-synthesis step: resolve the age key BEFORE the unit loop runs, so the
 * unit only renders. Two contacts, kept distinct and both passed in (instance-passing, the
 * registry-bound collaborators, never reached statically): it READS the {@code rke2-cluster} SSH
 * key through the shared {@link NdhKeystoreReader} (the ndh domain's single reader of the operator
 * key inventory — the same door cluster-pki reads its root through, so the keys.yaml navigation is
 * NOT duplicated here), then CONVERTS it through the {@link SshToAgeConverter} edge.
 *
 * <p>Fail-soft on absence, fail-fast on malformation: no key-store present ⟹ {@link
 * Optional#empty()} and the converter is NOT called (ephemeral / test runs, where the unit then
 * skips). A present-but-malformed key-store raises inside the reader — a defect to surface, not a
 * silent skip.
 */
final class SopsAgeMaterialResolver {

  private static final Logger LOG = LoggerFactory.getLogger(SopsAgeMaterialResolver.class);

  /** The ndh SSH key entry Flux's sops-age identity is derived from. */
  private static final String CLUSTER_SSH_KEY = NdhKeystoreCatalog.CLUSTER_SSH_KEY.entryName();

  private final SshToAgeConverter converter;
  private final NdhKeystoreReader keystore;
  private final Optional<EnclosureGate> enclosure;

  SopsAgeMaterialResolver(
      SshToAgeConverter converter, NdhKeystoreReader keystore, Optional<EnclosureGate> enclosure) {
    this.converter = converter;
    this.keystore = keystore;
    this.enclosure = enclosure;
  }

  Optional<SopsAgeMaterial> resolve() {
    // IN_CLUSTER the sops-age Secret is a NODE_BOOTSTRAP artifact already applied at the operator's
    // grow, and the git tree's key-store is sops-encrypted at rest — a render never re-derives it.
    // Gate before touching the key-store (its plaintext presence is an OPERATOR fact).
    if (enclosure.map(EnclosureGate::inCluster).orElse(false)) {
      LOG.debug("In-cluster render — sops-age Secret is a bootstrap artifact, not re-emitted");
      return Optional.empty();
    }
    if (!keystore.present()) {
      LOG.debug("No ndh key-store present — sops-age Secret will be skipped");
      return Optional.empty();
    }
    final String ageKey = converter.toAgeKey(keystore.sshPrivate(CLUSTER_SSH_KEY));
    return Optional.of(new SopsAgeMaterial(ageKey));
  }
}
