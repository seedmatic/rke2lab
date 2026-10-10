package io.seedmatic.rke2lab.auth.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.seedmatic.rke2lab.ghapp.contract.GhAppCoordinate;
import io.seedmatic.rke2lab.ghapp.contract.GithubAppCredentials;
import io.seedmatic.rke2lab.seed.broker.port.Parcel;
import io.seedmatic.rke2lab.seed.broker.testkit.InMemoryCellar;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The one token revealer: it reads the App from its OWNER's case and mints, and its three lanes
 * keep each caller's behaviour — mint only, mint or the pipeline's token, and the reader scope.
 */
class GithubAppTokensTest {

  private static final Parcel PARCEL = new Parcel("rke2lab", "bioskop-mgmt");
  private static final GithubAppCredentials APP = new GithubAppCredentials("1", "2", "pem");

  /** A mint that records what it was handed, so the test sees the record the revealer read. */
  private static final class RecordingMint implements GithubWriterTokenMint, GithubReaderTokenMint {
    private final String token;
    private final List<GithubAppCredentials> handed = new ArrayList<>();

    RecordingMint(String token) {
      this.token = token;
    }

    @Override
    public String mint(GithubAppCredentials app) {
      handed.add(app);
      return token;
    }
  }

  private static InMemoryCellar sealed() {
    final InMemoryCellar cellar = new InMemoryCellar();
    cellar.store(PARCEL, GhAppCoordinate.GITHUB_APP, APP);
    return cellar;
  }

  @Test
  void the_writer_mints_from_the_app_its_owner_sealed() {
    final RecordingMint writer = new RecordingMint("write-token");
    final GithubAppTokens tokens =
        new GithubAppTokens(Optional.of(writer), Optional.empty(), Map.of());

    assertEquals(Optional.of("write-token"), tokens.writer(sealed(), Optional.of(PARCEL)));
    assertEquals(List.of(APP), writer.handed, "the mint is handed the owner's record, unchanged");
  }

  @Test
  void no_token_is_fabricated_without_a_mint_a_plot_or_a_sealed_app() {
    final RecordingMint writer = new RecordingMint("write-token");
    final GithubAppTokens minting =
        new GithubAppTokens(Optional.of(writer), Optional.empty(), Map.of());

    assertEquals(
        Optional.empty(),
        new GithubAppTokens(Optional.empty(), Optional.empty(), Map.of())
            .writer(sealed(), Optional.of(PARCEL)),
        "a survey/preview filters the mint out");
    assertEquals(Optional.empty(), minting.writer(sealed(), Optional.empty()), "no plot");
    assertEquals(
        Optional.empty(),
        minting.writer(new InMemoryCellar(), Optional.of(PARCEL)),
        "the registration sealed nothing yet");
    assertEquals(List.of(), writer.handed);
  }

  @Test
  void the_pipeline_lane_is_the_fallback_and_the_app_mint_wins() {
    final Map<String, String> tekton = Map.of(GithubAppTokens.PIPELINE_TOKEN_ENV, " pac-token ");
    final RecordingMint writer = new RecordingMint("write-token");

    assertEquals(
        Optional.of("write-token"),
        new GithubAppTokens(Optional.of(writer), Optional.empty(), tekton)
            .writerOrPipeline(sealed(), Optional.of(PARCEL)),
        "an operator run never sets the variable, but if both are there the App mint wins");
    assertEquals(
        Optional.of("pac-token"),
        new GithubAppTokens(Optional.empty(), Optional.empty(), tekton)
            .writerOrPipeline(new InMemoryCellar(), Optional.empty()),
        "in a Tekton run there is no App to mint from: the pipeline's token, trimmed");
    assertEquals(
        Optional.empty(),
        new GithubAppTokens(
                Optional.empty(),
                Optional.empty(),
                Map.of(GithubAppTokens.PIPELINE_TOKEN_ENV, "  "))
            .pipeline(),
        "a blank variable is no token");
  }

  /**
   * The bump's lane: its writer never falls back. Pinned because folding the three copies into one
   * revealer is exactly the change that could have given it a fallback it never had.
   */
  @Test
  void the_writer_lane_never_falls_back_to_the_pipeline() {
    final Map<String, String> tekton = Map.of(GithubAppTokens.PIPELINE_TOKEN_ENV, "pac-token");

    assertEquals(
        Optional.empty(),
        new GithubAppTokens(Optional.empty(), Optional.empty(), tekton)
            .writer(sealed(), Optional.of(PARCEL)));
  }

  @Test
  void the_reader_lane_uses_the_reader_mint_and_never_the_writer() {
    final RecordingMint writer = new RecordingMint("write-token");
    final RecordingMint reader = new RecordingMint("read-token");

    assertEquals(
        Optional.of("read-token"),
        new GithubAppTokens(Optional.of(writer), Optional.of(reader), Map.of())
            .reader(sealed(), Optional.of(PARCEL)));
    assertEquals(List.of(), writer.handed, "a node's config fetch must not hold contents:write");
    assertEquals(List.of(APP), reader.handed);
  }
}
