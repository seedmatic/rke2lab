package io.seedmatic.rke2lab.manifests.bdd;

import com.fasterxml.jackson.databind.JsonNode;
import io.seedmatic.rke2lab.clusterpki.contract.SopsDecryptor;
import io.seedmatic.rke2lab.manifests.contract.BranchSecretCarrier;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import java.io.UncheckedIOException;
import java.util.Optional;

/**
 * The in-cluster cellar asset a render reads back from its branch head (§ in-cluster-cellar-asset)
 * — THREE-valued, like the recorded facet: empty only when HEAD carries no asset; the sealed
 * envelopes when it carries one; a REFUSAL when it carries one this render cannot open or read.
 *
 * <p>⚠️ It used to answer empty for an unreadable carrier and for one without envelopes too. A
 * secret-blind in-cluster render then rehydrated nothing, its IN_CLUSTER reveals found nothing, the
 * Secrets they feed rendered empty, and Flux pruned the populated ones. Absence is legitimate;
 * unreadability is a defect that must not be answered with a plausible empty.
 */
final class InClusterAssetAtHead {

  /** The {@code stringData} key the carrier holds its sealed envelopes under. */
  static final String ENVELOPES_KEY = "envelopes";

  private final SopsDecryptor decryptor;
  private final SeedCodec codec = new SeedCodec();

  InClusterAssetAtHead(SopsDecryptor decryptor) {
    this.decryptor = decryptor;
  }

  /** The sealed envelopes HEAD's carrier holds, or empty when HEAD carries none. */
  Optional<String> envelopes(RenderSoil soil) {
    final String file = BranchSecretCarrier.IN_CLUSTER_CELLAR.file();
    return soil.readAtHead(file).map(carrier -> envelopesOf(file, opened(file, carrier)));
  }

  /**
   * The carrier opened with the key sops finds in this enclosure — or a refusal naming it when it
   * does not open (sealed to recipients this enclosure no longer holds). The edge's message is
   * kept: it never carries sops' stderr, only the operation and a fixed classification.
   */
  private String opened(String file, String carrier) {
    try {
      return decryptor.decryptYaml(carrier);
    } catch (RuntimeException undecryptable) {
      throw new IllegalStateException(
          "HEAD carries "
              + file
              + " but it does not open here ("
              + undecryptable.getMessage()
              + "); refusing to render as though the branch carried no in-cluster asset");
    }
  }

  /**
   * The envelopes of an OPENED carrier. The refusals name the file and never its content, and carry
   * no cause: a parser's message quotes the source, and the source is the decrypted carrier.
   */
  private String envelopesOf(String file, String opened) {
    final JsonNode envelopes;
    try {
      envelopes = codec.reading().readYaml(opened).path("stringData").path(ENVELOPES_KEY);
    } catch (UncheckedIOException unreadable) {
      throw new IllegalStateException(
          "HEAD carries "
              + file
              + " but its opened YAML is unreadable; refusing to render as"
              + " though the branch carried no in-cluster asset");
    }
    if (envelopes.isMissingNode() || envelopes.isNull() || envelopes.asText().isBlank()) {
      throw new IllegalStateException(
          "HEAD carries "
              + file
              + " but it holds no stringData."
              + ENVELOPES_KEY
              + "; refusing to"
              + " render as though the branch carried no in-cluster asset");
    }
    return envelopes.asText();
  }
}
