package io.seedmatic.rke2lab.ghapp.edge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.seedmatic.rke2lab.ghapp.contract.GithubAppCredentials;
import io.seedmatic.rke2lab.ghapp.contract.GithubAppWebhookConfigurer;
import io.seedmatic.rke2lab.ghapp.contract.WebhookConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.time.Duration;
import java.time.Instant;
import org.osgi.service.component.annotations.Component;

/**
 * The realised ghapp webhook-reconcile edge: satisfies {@link GithubAppWebhookConfigurer} by
 * signing the App JWT (see {@link AppJwt}) and calling {@code PATCH /app/hook/config} with the
 * desired {@link WebhookConfig} — re-points the App's webhook {@code url} (on a funnel rename) and
 * sets its {@code secret}, the App-level settings the operator would otherwise change by hand in
 * the GitHub UI. Content type is {@code json}; {@code insecure_ssl} comes from the config (it is
 * STATE, because a staging funnel cert requires verification off and a constant here undid that) —
 * edge constants, not state. Pure JDK {@code HttpClient} + jackson + BouncyCastle; no embedded
 * jars.
 *
 * <p>Fail-fast (the predictability invariant): a non-2xx response throws — never a silent no-op.
 *
 * <p>Tagged {@code rke2lab.gardening=cultivating}: a live GitHub contact, filtered out under a
 * survey/preview frontier so a consuming scenario PENDS rather than calling the API.
 */
@Component(service = GithubAppWebhookConfigurer.class, property = "rke2lab.gardening=cultivating")
public final class GithubAppWebhookConfigurerEdge implements GithubAppWebhookConfigurer {

  private static final URI API = URI.create("https://api.github.com");
  private static final String API_VERSION = "2022-11-28";

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
  private final ObjectMapper mapper = new ObjectMapper();

  @Override
  public void configure(GithubAppCredentials credentials, WebhookConfig config) {
    final PrivateKey key = AppJwt.readPrivateKey(credentials.privateKeyPem());
    final String jwt = AppJwt.issue(credentials.appId(), key, Instant.now(), mapper);
    final ObjectNode body = mapper.createObjectNode();
    body.put("url", config.url());
    body.put("content_type", "json");
    body.put("secret", config.secret());
    body.put("insecure_ssl", config.insecureSsl() ? "1" : "0");
    final HttpResponse<String> response = patch("/app/hook/config", jwt, body);
    if (response.statusCode() / 100 != 2) {
      throw new IllegalStateException(
          "reconciling the App webhook config failed: HTTP "
              + response.statusCode()
              + " "
              + response.body());
    }
  }

  private HttpResponse<String> patch(String path, String jwt, ObjectNode body) {
    try {
      final HttpRequest request =
          HttpRequest.newBuilder()
              .uri(API.resolve(path))
              .header("Authorization", "Bearer " + jwt)
              .header("Accept", "application/vnd.github+json")
              .header("X-GitHub-Api-Version", API_VERSION)
              .method(
                  "PATCH",
                  HttpRequest.BodyPublishers.ofString(
                      mapper.writeValueAsString(body), StandardCharsets.UTF_8))
              .build();
      return http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new UncheckedIOException("GitHub PATCH " + path + " failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("GitHub PATCH " + path + " interrupted", e);
    }
  }
}
