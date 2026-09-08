package io.seedmatic.rke2lab.ghapp.contract;

/**
 * The permission subset an installation token is minted for — one App, many least-privilege tokens.
 * A mint call requests only this subset of the App's declared permissions, so a reader can never
 * mint a writer's token. The {@code ghapp-edge} maps each case to the GitHub API {@code
 * permissions} object at mint time.
 */
public enum TokenScope {

  /** {@code contents:write} — the render push (the writer, host-side). */
  WRITER,

  /** {@code contents:read} — the Flux pull (rendered natively by the Flux Operator). */
  READER,

  /** {@code statuses:write} + {@code pull_requests:write} + {@code checks:write} — Tekton. */
  CI,

  /**
   * {@code administration:write} — repository webhook management (the per-cluster PaC/Tekton
   * webhook reconcile, {@code POST/PATCH /repos/&#123;repo&#125;/hooks}). Requires the App to hold
   * the "Repository administration" permission.
   */
  REPO_ADMIN
}
