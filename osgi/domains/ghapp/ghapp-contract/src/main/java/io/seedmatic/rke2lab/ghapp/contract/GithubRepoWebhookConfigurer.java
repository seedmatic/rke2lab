package io.seedmatic.rke2lab.ghapp.contract;

/**
 * The ghapp domain's REPOSITORY webhook-reconcile verb: from the sealed {@link
 * GithubAppCredentials} mint a {@link TokenScope#REPO_ADMIN} installation token and reconcile ONE
 * repo webhook via {@code POST/PATCH /repos/&#123;repo&#125;/hooks}. The per-cluster twin of {@link
 * GithubAppWebhookConfigurer} (which sets the App-level webhook, singular): a repo accepts many
 * webhooks, so each cluster's PaC/Tekton funnel gets its own delivery independently.
 *
 * <p>Idempotent and fail-fast: keyed by {@link RepoWebhookConfig#url()}, an existing hook is
 * patched and a missing one is created; a non-2xx response throws — never a silent no-op. Repo
 * webhooks need the App's "Repository administration" permission (hence {@link
 * TokenScope#REPO_ADMIN}), not the App-level JWT.
 */
public interface GithubRepoWebhookConfigurer {

  /**
   * Reconcile the repository webhook described by {@code config}, authenticating with an
   * installation token minted from {@code credentials}.
   */
  void configure(GithubAppCredentials credentials, RepoWebhookConfig config);
}
