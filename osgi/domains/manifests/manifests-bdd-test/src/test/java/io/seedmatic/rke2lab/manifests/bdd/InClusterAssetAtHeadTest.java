package io.seedmatic.rke2lab.manifests.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.clusterpki.contract.SopsDecryptor;
import io.seedmatic.rke2lab.manifests.contract.BranchSecretCarrier;
import io.seedmatic.rke2lab.worktree.CommitSpec;
import io.seedmatic.rke2lab.worktree.TransientWorktree;
import io.seedmatic.rke2lab.worktree.TreeEdit;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The in-cluster asset read back at HEAD is three-valued: absent → empty, readable → its envelopes,
 * present but unreadable → a refusal naming the file and nothing of its content.
 */
class InClusterAssetAtHeadTest {

  private static final String FILE = BranchSecretCarrier.IN_CLUSTER_CELLAR.file();
  private static final String MARKER = "SECRET-MARKER-7f3a";

  @Test
  void no_carrier_at_head_is_a_legitimate_empty() {
    assertEquals(Optional.empty(), asset().envelopes(soil(Map.of())));
  }

  @Test
  void a_readable_carrier_yields_its_envelopes() {
    final String opened = carrier("  envelopes: '{\"coordinate\":\"" + MARKER + "\"}'\n");
    assertEquals(
        Optional.of("{\"coordinate\":\"" + MARKER + "\"}"),
        asset().envelopes(soil(Map.of(FILE, opened))));
  }

  @Test
  void a_carrier_without_envelopes_refuses_the_render_by_name() {
    assertRefused(asset(), carrier("  other: " + MARKER + "\n"));
  }

  @Test
  void a_carrier_with_blank_envelopes_refuses_the_render() {
    assertRefused(asset(), carrier("  envelopes: ''\n  other: " + MARKER + "\n"));
  }

  @Test
  void an_unreadable_carrier_refuses_the_render_without_quoting_it() {
    assertRefused(asset(), "stringData: [ " + MARKER + "\n  : : {");
  }

  @Test
  void a_carrier_that_does_not_open_refuses_the_render_without_quoting_sops() {
    assertRefused(new InClusterAssetAtHead(new UndecryptableCarrier()), "sops: sealed\n");
  }

  private static void assertRefused(InClusterAssetAtHead asset, String atHead) {
    final IllegalStateException refused =
        assertThrows(
            IllegalStateException.class, () -> asset.envelopes(soil(Map.of(FILE, atHead))));
    assertTrue(refused.getMessage().contains(FILE), refused.getMessage());
    assertFalse(
        refused.getMessage().contains(MARKER), "the refusal carries nothing of the carrier");
    assertNull(refused.getCause(), "no cause: a parser's message would quote the decrypted source");
  }

  private static String carrier(String stringData) {
    return "apiVersion: v1\nkind: Secret\nmetadata:\n  name: in-cluster-cellar\nstringData:\n"
        + stringData;
  }

  /** A decryptor that hands back what it is given: the opened carrier is the test's input. */
  private static InClusterAssetAtHead asset() {
    return new InClusterAssetAtHead(
        new SopsDecryptor() {
          @Override
          public String decryptYaml(String sopsYaml, String ageIdentity) {
            return sopsYaml;
          }

          @Override
          public String decryptYaml(String sopsYaml) {
            return sopsYaml;
          }
        });
  }

  /**
   * A decryptor that fails the way the sops edge does for a carrier sealed to recipients this
   * enclosure no longer holds — its message quoting sops' stderr, here the marker.
   */
  private static final class UndecryptableCarrier implements SopsDecryptor {
    @Override
    public String decryptYaml(String sopsYaml, String ageIdentity) {
      return decryptYaml(sopsYaml);
    }

    @Override
    public String decryptYaml(String sopsYaml) {
      throw new IllegalStateException(
          "sops --decrypt exited with code 128: no key could decrypt the data key; " + MARKER);
    }
  }

  /** A head whose committed files are {@code atHead}; nothing else of it is used. */
  private static RenderSoil soil(Map<String, String> atHead) {
    final TransientWorktree head =
        new TransientWorktree() {
          @Override
          public String branch() {
            return "manifests/test-mgmt";
          }

          @Override
          public String head() {
            return "0000000000000000000000000000000000000000";
          }

          @Override
          public Optional<String> readAtHead(String path) {
            return Optional.ofNullable(atHead.get(path));
          }

          @Override
          public String commit(TreeEdit edit, CommitSpec spec) {
            throw new UnsupportedOperationException();
          }

          @Override
          public void publish(Optional<String> pushToken, Duration timeout) {
            throw new UnsupportedOperationException();
          }
        };
    return new RenderSoil(head, Path.of("/unused"));
  }
}
