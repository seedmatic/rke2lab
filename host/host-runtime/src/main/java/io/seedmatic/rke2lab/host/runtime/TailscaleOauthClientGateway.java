package io.seedmatic.rke2lab.host.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.seedmatic.rke2lab.seed.broker.port.SecretsGateway;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * A {@link SecretsGateway} realisation that serves ONLY the {@code tailscale} block, sourced from
 * the OAuth client secret ndh provisions on the operator host — NOT from rke2lab's {@code
 * .secrets}.
 *
 * <p>Single source of trust: ndh owns the tailnet, mints/rotates the long-lived OAuth client
 * ({@code tskey-client-…}), and materialises it via sops-nix. sops only lands it under {@code
 * /run/secrets} (a root-traversed tmpfs the seed process / an automounted container can't reach),
 * so ndh's {@code userSecretMirror} copies it to a persistent, user-owned file at {@code
 * ~/.local/share/ndh/tailnet.tailscale.client}. rke2lab holds NO tailscale creds; seed-outcluster
 * reads that user path here (resolved against {@code user.home} — the operator hosts have different
 * homes).
 *
 * <p>The file is a bare scalar {@code tskey-client-<CLIENT_ID>-<rest>}. This gateway derives the
 * pair the k8s-operator needs and presents it as the SAME shape the old {@code .secrets} tailscale
 * block had — {@code {"oauth":{"id":<CLIENT_ID>,"token":<full scalar>}}} — so {@code
 * ReplicatorSecretsSealScenario} consumes it unchanged. Chained ahead of {@link DotSecretsGateway}
 * (which serves the {@code kubernetes} mapping + {@code tekton}); every non-{@code tailscale} path
 * falls through.
 */
public final class TailscaleOauthClientGateway implements SecretsGateway {

  /** The user-owned path ndh's {@code userSecretMirror} writes the OAuth client to. */
  public static final Path NDH_CLIENT_PATH =
      Path.of(System.getProperty("user.home"), ".local/share/ndh/tailnet.tailscale.client");

  private static final String BLOCK = "tailscale";
  private static final String CLIENT_PREFIX = "tskey-client-";
  private static final ObjectMapper JSON = JsonMapper.builder().build();

  private final Path clientFile;

  public TailscaleOauthClientGateway() {
    this(NDH_CLIENT_PATH);
  }

  public TailscaleOauthClientGateway(final Path clientFile) {
    this.clientFile = clientFile;
  }

  @Override
  public Optional<String> read(final String dottedPath) {
    final String[] parts = dottedPath.split("\\.");
    if (parts.length == 0 || !BLOCK.equals(parts[0]) || !Files.isReadable(clientFile)) {
      return Optional.empty();
    }
    final String secret;
    try {
      secret = Files.readString(clientFile).strip();
    } catch (final IOException ex) {
      throw new UncheckedIOException(
          "failed to read the tailscale OAuth client at " + clientFile, ex);
    }
    final Optional<String> id = clientId(secret);
    if (id.isEmpty()) {
      return Optional.empty();
    }

    final ObjectNode oauth = JSON.createObjectNode();
    oauth.put("id", id.get());
    oauth.put("token", secret);
    final ObjectNode tailscale = JSON.createObjectNode();
    tailscale.set("oauth", oauth);

    JsonNode node = tailscale;
    for (int i = 1; i < parts.length; i++) {
      node = node.path(parts[i]);
    }
    if (node.isMissingNode() || node.isNull()) {
      return Optional.empty();
    }
    try {
      return Optional.of(JSON.writeValueAsString(node));
    } catch (final JsonProcessingException ex) {
      throw new UncheckedIOException("failed to render the tailscale oauth block", ex);
    }
  }

  /**
   * The OAuth client id embedded in {@code tskey-client-<id>-<rest>}, empty if the shape differs.
   */
  private Optional<String> clientId(final String secret) {
    if (!secret.startsWith(CLIENT_PREFIX)) {
      return Optional.empty();
    }
    final String afterPrefix = secret.substring(CLIENT_PREFIX.length());
    final int dash = afterPrefix.indexOf('-');
    if (dash <= 0) {
      return Optional.empty();
    }
    return Optional.of(afterPrefix.substring(0, dash));
  }
}
