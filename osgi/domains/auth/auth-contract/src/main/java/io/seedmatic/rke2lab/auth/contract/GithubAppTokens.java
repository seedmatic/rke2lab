package io.seedmatic.rke2lab.auth.contract;

import io.seedmatic.rke2lab.ghapp.contract.GhAppCoordinate;
import io.seedmatic.rke2lab.ghapp.contract.GithubAppCredentials;
import io.seedmatic.rke2lab.seed.broker.port.Cellar;
import io.seedmatic.rke2lab.seed.broker.port.Parcel;
import java.util.Map;
import java.util.Optional;

/**
 * The ONE place a GitHub token is obtained for this project — from the one org-owned App, or, in a
 * Tekton run, from the token Pipelines-as-Code already minted. Every consumer asks it for a TOKEN
 * and never touches the App material: the manifests render and delivery, the version bumper, the
 * fabric delivery.
 *
 * <p>It reads the App credentials from their OWNER — {@link GhAppCoordinate#GITHUB_APP}, the case
 * the ghapp registration seals {@link GithubAppCredentials} at. A consumer asks it for a token and
 * grows no "reveal the App, then mint" of its own: that logic exists once, here.
 *
 * <p>The lanes are named, not merged, because their callers legitimately differ: {@link #writer}
 * mints only, {@link #writerOrPipeline} falls back to the pipeline's token when there is no App to
 * mint from (a render inside Tekton), {@link #reader} mints the read-only token a grown node
 * fetches its config with, and {@link #readerOrPipeline} is the fetch's lane, falling back the same
 * way. Flattening them would silently give the version bumper a fallback it never had.
 *
 * <p>A record because it holds collaborators and no state of its own, and because a {@code
 * type=contract} bundle exports only records, enums, sealed types and interfaces. It is built where
 * it is used, from the scenario's injected mints, since those are only known once the stage creator
 * has resolved them.
 *
 * @param writerMint the {@code contents:write} mint; empty under a survey/preview, whose frontier
 *     filters out the {@code cultivating}-gated edge — no token is then fabricated
 * @param readerMint the {@code contents:read} mint, gated the same way
 * @param environment the process environment the pipeline lane reads {@link #PIPELINE_TOKEN_ENV}
 *     from — passed in rather than read statically, so the lane is testable and the dependency on
 *     the environment is visible at the call site
 */
public record GithubAppTokens(
    Optional<GithubWriterTokenMint> writerMint,
    Optional<GithubReaderTokenMint> readerMint,
    Map<String, String> environment) {

  /**
   * The variable the in-cluster render-publish step exports the Pipelines-as-Code App token
   * through. Named once here; the shell that EXPORTS it lives in a rendered Tekton step, which a
   * test ties to this constant so the two spellings cannot drift apart.
   */
  public static final String PIPELINE_TOKEN_ENV = "RKE2LAB_PUSH_TOKEN";

  public GithubAppTokens {
    environment = Map.copyOf(environment);
  }

  /** A fresh {@code contents:write} token minted from the sealed App, or empty. */
  public Optional<String> writer(Cellar cellar, Optional<Parcel> parcel) {
    return writerMint.flatMap(mint -> app(cellar, parcel).map(mint::mint));
  }

  /**
   * {@link #writer}, and failing that the token the pipeline handed in. The App mint wins when both
   * are reachable: an operator run never sets the variable.
   */
  public Optional<String> writerOrPipeline(Cellar cellar, Optional<Parcel> parcel) {
    return writer(cellar, parcel).or(this::pipeline);
  }

  /** The token Pipelines-as-Code handed to this run, or empty outside a Tekton run. */
  public Optional<String> pipeline() {
    return Optional.ofNullable(environment.get(PIPELINE_TOKEN_ENV))
        .map(String::trim)
        .filter(token -> !token.isEmpty());
  }

  /** A fresh {@code contents:read} token minted from the sealed App, or empty. */
  public Optional<String> reader(Cellar cellar, Optional<Parcel> parcel) {
    return readerMint.flatMap(mint -> app(cellar, parcel).map(mint::mint));
  }

  /**
   * {@link #reader}, and failing that the token the pipeline handed in — the lane a fetch
   * authenticates with, so it is the App's identity wherever one exists: Pipelines-as-Code mints
   * the pipeline token from the same App. That token is {@code contents:write}, more than a fetch
   * needs, and acceptable because it is what that environment holds; a run that can mint never
   * reaches it, and nothing here mints a write token for a read.
   */
  public Optional<String> readerOrPipeline(Cellar cellar, Optional<Parcel> parcel) {
    return reader(cellar, parcel).or(this::pipeline);
  }

  private Optional<GithubAppCredentials> app(Cellar cellar, Optional<Parcel> parcel) {
    return parcel.flatMap(
        run -> cellar.fetch(run, GhAppCoordinate.GITHUB_APP, GithubAppCredentials.class));
  }
}
