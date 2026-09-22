package io.seedmatic.rke2lab.ghapp.contract;

/**
 * The desired state of the one org-owned GitHub App's WEBHOOK configuration — the pair {@code PATCH
 * /app/hook/config} reconciles: the {@code url} the App POSTs its events to (the Tailscale funnel
 * FQDN, which changes on a funnel rename) and the {@code secret} the HMAC signature is computed
 * with (the single shared {@code github.webhook.secret} Pipelines-as-Code validates against), and
 * {@code insecureSsl} — whether GitHub should SKIP TLS verification, which it must while the funnel
 * serves a STAGING Let's Encrypt cert.
 *
 * <p>{@code insecureSsl} is STATE, not an edge constant, for one measured reason: as a constant it
 * was re-asserted on every grow, so a grow silently undid an operator's manual "verification off"
 * mid-validation and stalled the render loop. Deriving it from {@code FunnelCertIssuance} makes the
 * pair impossible to half-flip — returning to production turns verification back on by
 * construction. Content type stays an edge constant (JSON).
 *
 * <p>NOT the App's event SUBSCRIPTIONS nor its permissions: those are set once at registration (the
 * pre-filled form's {@code events[]} + permission params) and are not reachable through the hook
 * config endpoint. This record is only the two fields that legitimately drift after creation.
 */
public record WebhookConfig(String url, String secret, boolean insecureSsl) {}
