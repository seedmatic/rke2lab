package io.seedmatic.rke2lab.ghapp.edge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.seedmatic.rke2lab.ghapp.contract.GithubAppCredentials;
import io.seedmatic.rke2lab.ghapp.contract.GithubAppMinter;
import io.seedmatic.rke2lab.ghapp.contract.MintedToken;
import io.seedmatic.rke2lab.ghapp.contract.TokenScope;
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
 * The realised ghapp mint edge: satisfies {@link GithubAppMinter} by signing the App JWT (see
 * {@link AppJwt}) and calling {@code POST /app/installations/&#123;id&#125;/access_tokens} with the
 * {@link TokenScope}'s permission subset — one App, a least-privilege token per scope. Pure JDK
 * {@code HttpClient} + jackson + BouncyCastle; no embedded jars.
 *
 * <p>Fail-fast (the predictability invariant): a non-201 response or a missing field throws — never
 * a silent fallback to another credential source.
 *
 * <p>Tagged {@code rke2lab.gardening=cultivating}: a live GitHub contact, filtered out under a
 * survey/preview frontier so a consuming scenario PENDS rather than calling the API.
 */
@Component(service = GithubAppMinter.class, property = "rke2lab.gardening=cultivating")
public final class GithubAppTokenMinter implements GithubAppMinter {

  private static final URI API = URI.create("https://api.github.com");
  private static final String API_VERSION = "2022-11-28";

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
  private final ObjectMapper mapper = new ObjectMapper();

  @Override
  public MintedToken mint(GithubAppCredentials credentials, TokenScope scope) {
    final PrivateKey key = AppJwt.readPrivateKey(credentials.privateKeyPem());
    final String jwt = AppJwt.issue(credentials.appId(), key, Instant.now(), mapper);
    final ObjectNode body = mapper.createObjectNode();
    body.set("permissions", permissionsFor(scope));
    final HttpResponse<String> response =
        post("/app/installations/" + credentials.installationId() + "/access_tokens", jwt, body);
    if (response.statusCode() != 201) {
      throw new IllegalStateException(
          "minting the "
              + scope
              + " token failed: HTTP "
              + response.statusCode()
              + " "
              + response.body());
    }
    final JsonNode token = read(response.body());
    return new MintedToken(
        requiredText(token, "token"), Instant.parse(requiredText(token, "expires_at")));
  }

  private ObjectNode permissionsFor(TokenScope scope) {
    final ObjectNode permissions = mapper.createObjectNode();
    switch (scope) {
      case WRITER -> permissions.put("contents", "write");
      case READER -> permissions.put("contents", "read");
      case CI ->
          permissions.put("statuses", "write").put("pull_requests", "write").put("checks", "write");
      case REPO_ADMIN -> permissions.put("administration", "write");
    }
    return permissions;
  }

  private HttpResponse<String> post(String path, String jwt, ObjectNode body) {
    try {
      final HttpRequest request =
          HttpRequest.newBuilder()
              .uri(API.resolve(path))
              .header("Authorization", "Bearer " + jwt)
              .header("Accept", "application/vnd.github+json")
              .header("X-GitHub-Api-Version", API_VERSION)
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      mapper.writeValueAsString(body), StandardCharsets.UTF_8))
              .build();
      return http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new UncheckedIOException("GitHub POST " + path + " failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("GitHub POST " + path + " interrupted", e);
    }
  }

  private JsonNode read(String body) {
    try {
      return mapper.readTree(body);
    } catch (IOException e) {
      throw new UncheckedIOException("could not parse GitHub response", e);
    }
  }

  private static String requiredText(JsonNode node, String field) {
    final JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      throw new IllegalStateException("GitHub response missing '" + field + "'");
    }
    return value.asText();
  }
}
