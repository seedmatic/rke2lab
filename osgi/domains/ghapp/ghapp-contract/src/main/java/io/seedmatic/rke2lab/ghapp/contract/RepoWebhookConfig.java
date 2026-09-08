package io.seedmatic.rke2lab.ghapp.contract;

import java.util.List;

/**
 * The desired state of ONE repository webhook the ghapp domain reconciles via {@code POST/PATCH
 * /repos/&#123;repo&#125;/hooks}: the {@code repoFullName} ({@code owner/repo}) it lives on, the
 * funnel {@code url} GitHub POSTs to, the shared HMAC {@code secret}, and the {@code events} it
 * subscribes to. Unlike the App-level webhook ({@link WebhookConfig}, singular per App), a repo
 * accepts MANY webhooks — one per cluster's PaC funnel — so a workload cluster's Tekton is driven
 * independently of the management cluster's.
 *
 * <p>The webhook is IDENTIFIED by its {@code url} (a per-cluster funnel FQDN, e.g. {@code
 * pipelines-webhook-<host>-<role>.<tailnet>.ts.net}), so the reconcile is idempotent: an existing
 * hook with the same url is patched, otherwise a new one is created — no duplicate per cluster.
 */
public record RepoWebhookConfig(
    String repoFullName, String url, String secret, List<String> events) {

  public RepoWebhookConfig {
    events = List.copyOf(events);
  }
}
