package io.seedmatic.rke2lab.manifests;

import io.seedmatic.rke2lab.manifests.contract.profiles.SigningKeyMaterial;
import io.seedmatic.rke2lab.ndh.contract.NdhKeystoreCatalog;
import io.seedmatic.rke2lab.ndh.contract.NdhKeystoreReader;
import io.seedmatic.rke2lab.seed.broker.port.EnclosureGate;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The pre-synthesis resolver for the commit-signing key — the twin of {@link
 * SopsAgeMaterialResolver}. Reads the {@code github-signing} SSH private from the shared {@link
 * NdhKeystoreReader} (the ndh domain's single door) so the OPERATOR grow can emit the {@code
 * manifests-render-signing} Secret the in-cluster render mounts.
 *
 * <p>Enclosure-gated: IN_CLUSTER the git tree's key-store is sops-encrypted at rest and the Secret
 * is already applied (a NODE_BOOTSTRAP artifact from the operator's grow), so the resolver skips
 * rather than reads — {@link Optional#empty()}, and the unit renders nothing. Fail-soft on an
 * absent key-store (bare survey / test) for the same reason; fail-fast on a present-but-malformed
 * one (the reader raises), a defect to surface.
 */
final class SigningKeyMaterialResolver {

  private static final Logger LOG = LoggerFactory.getLogger(SigningKeyMaterialResolver.class);

  /** The ndh key-store entry the rke2lab bot's rendered commit is signed with. */
  static final String SIGNING_KEY = NdhKeystoreCatalog.SIGNING_KEY.entryName();

  private final NdhKeystoreReader keystore;
  private final Optional<EnclosureGate> enclosure;

  SigningKeyMaterialResolver(NdhKeystoreReader keystore, Optional<EnclosureGate> enclosure) {
    this.keystore = keystore;
    this.enclosure = enclosure;
  }

  Optional<SigningKeyMaterial> resolve() {
    if (enclosure.map(EnclosureGate::inCluster).orElse(false)) {
      LOG.debug(
          "In-cluster render — the signing-key Secret is a bootstrap artifact, not re-emitted");
      return Optional.empty();
    }
    if (!keystore.present()) {
      LOG.debug("No ndh key-store present — the signing-key Secret will be skipped");
      return Optional.empty();
    }
    return Optional.of(new SigningKeyMaterial(keystore.sshPrivate(SIGNING_KEY)));
  }
}
