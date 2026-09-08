package io.seedmatic.rke2lab.ghapp.edge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.seedmatic.rke2lab.ghapp.contract.GithubAppCredentials;
import io.seedmatic.rke2lab.ghapp.contract.GithubAppMinter;
import io.seedmatic.rke2lab.ghapp.contract.GithubRepoWebhookConfigurer;
import io.seedmatic.rke2lab.ghapp.contract.MintedToken;
import io.seedmatic.rke2lab.ghapp.contract.RepoWebhookConfig;
import io.seedmatic.rke2lab.ghapp.contract.TokenScope;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * The realised ghapp REPOSITORY webhook-reconcile edge: satisfies {@link
 * GithubRepoWebhookConfigurer} by minting a {@link TokenScope#REPO_ADMIN} installation token (via
 * {@link GithubAppMinter}) and reconciling one repo webhook through {@code GET/POST/PATCH
 * /repos/&#123;repo&#125;/hooks}. Keyed by the config URL: an existing hook with the same funnel
 * url is patched, otherwise a new hook is created — idempotent, one per cluster.
 *
 * <p>Content type is {@code json} and {@code insecure_ssl} is {@code 0} (verified TLS). Fail-fast:
 * a non-2xx response throws — never a silent no-op. Pure JDK {@code HttpClient} + jackson.
 *
 * <p>Tagged {@code rke2lab.gardening=cultivating}: a live GitHub contact, filtered out under a
 * survey/preview frontier so a consuming scenario PENDS rather than calling the API.
 */
@Component(service = GithubRepoWebhookConfigurer.class, property = "rke2lab.gardening=cultivating")
public final class GithubRepoWebhookConfigurerEdge implements GithubRepoWebhookConfigurer {

  private static final URI API = URI.create("https://api.github.com");
  private static final String API_VERSION = "2022-11-28";

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
  private final ObjectMapper mapper = new ObjectMapper();

  @Reference private GithubAppMinter minter;

  @Override
  public void configure(GithubAppCredentials credentials, RepoWebhookConfig config) {
    final MintedToken token = minter.mint(credentials, TokenScope.REPO_ADMIN);
    final String hooksPath = "/repos/" + config.repoFullName() + "/hooks";

    final long existingId = findHookIdByUrl(token.token(), hooksPath, config.url());
    final ObjectNode body = desiredHook(config);
    final HttpResponse<String> response =
        existingId >= 0
            ? send("PATCH", hooksPath + "/" + existingId, token.token(), body)
            : send("POST", hooksPath, token.token(), body);
    if (response.statusCode() / 100 != 2) {
      throw new IllegalStateException(
          "reconciling the repo webhook "
              + config.url()
              + " on "
              + config.repoFullName()
              + " failed: HTTP "
              + response.statusCode()
              + " "
              + response.body());
    }
  }

  /**
   * The GitHub hook body: {@code {name:web, active, events, config:{url,content_type,secret,…}}}.
   */
  private ObjectNode desiredHook(RepoWebhookConfig config) {
    final ObjectNode body = mapper.createObjectNode();
    body.put("name", "web");
    body.put("active", true);
    final ArrayNode events = body.putArray("events");
    config.events().forEach(events::add);
    final ObjectNode hookConfig = body.putObject("config");
    hookConfig.put("url", config.url());
    hookConfig.put("content_type", "json");
    hookConfig.put("secret", config.secret());
    hookConfig.put("insecure_ssl", "0");
    return body;
  }

  /** The id of the repo hook whose {@code config.url} equals {@code url}, or {@code -1} if none. */
  private long findHookIdByUrl(String token, String hooksPath, String url) {
    final HttpResponse<String> response = send("GET", hooksPath + "?per_page=100", token, null);
    if (response.statusCode() / 100 != 2) {
      throw new IllegalStateException(
          "listing repo webhooks on " + hooksPath + " failed: HTTP " + response.statusCode());
    }
    for (JsonNode hook : read(response.body())) {
      if (url.equals(hook.path("config").path("url").asText(null))) {
        return hook.path("id").asLong(-1);
      }
    }
    return -1;
  }

  private HttpResponse<String> send(
      String method, String path, String token, @Nullable ObjectNode body) {
    try {
      final HttpRequest.BodyPublisher publisher =
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(
                  mapper.writeValueAsString(body), StandardCharsets.UTF_8);
      final HttpRequest request =
          HttpRequest.newBuilder()
              .uri(API.resolve(path))
              .header("Authorization", "Bearer " + token)
              .header("Accept", "application/vnd.github+json")
              .header("X-GitHub-Api-Version", API_VERSION)
              .method(method, publisher)
              .build();
      return http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new UncheckedIOException("GitHub " + method + " " + path + " failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("GitHub " + method + " " + path + " interrupted", e);
    }
  }

  private JsonNode read(String body) {
    try {
      return mapper.readTree(body);
    } catch (IOException e) {
      throw new UncheckedIOException("parsing GitHub response failed", e);
    }
  }
}
