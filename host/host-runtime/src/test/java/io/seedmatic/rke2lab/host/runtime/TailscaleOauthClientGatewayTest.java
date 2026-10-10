package io.seedmatic.rke2lab.host.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.seedmatic.rke2lab.seed.broker.port.SecretsGateway;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TailscaleOauthClientGatewayTest {

  private static final SeedCodec CODEC = new SeedCodec();

  @Test
  void servesTheTailscaleBlockDerivedFromTheNdhClientScalar(@TempDir Path dir) throws IOException {
    final Path client = dir.resolve("tailnet.tailscale.client");
    Files.writeString(client, "tskey-client-kfadHk1iNq11CNTRL-abc123def456\n");
    final SecretsGateway gw = new TailscaleOauthClientGateway(client);

    final JsonNode block = CODEC.decode(gw.read("tailscale").orElseThrow());
    assertEquals("kfadHk1iNq11CNTRL", block.at("/oauth/id").asText());
    // token is the FULL scalar (stripped), the k8s-operator's client_secret
    assertEquals("tskey-client-kfadHk1iNq11CNTRL-abc123def456", block.at("/oauth/token").asText());
  }

  @Test
  void navigatesDottedSubpaths(@TempDir Path dir) throws IOException {
    final Path client = dir.resolve("tailnet.tailscale.client");
    Files.writeString(client, "tskey-client-ID42-secretpart");
    final SecretsGateway gw = new TailscaleOauthClientGateway(client);

    assertEquals("\"ID42\"", gw.read("tailscale.oauth.id").orElseThrow());
  }

  @Test
  void servesOnlyTheTailscaleBlock(@TempDir Path dir) throws IOException {
    final Path client = dir.resolve("tailnet.tailscale.client");
    Files.writeString(client, "tskey-client-ID42-secretpart");
    final SecretsGateway gw = new TailscaleOauthClientGateway(client);

    assertTrue(gw.read("kubernetes").isEmpty());
    assertTrue(gw.read("github").isEmpty());
  }

  @Test
  void emptyWhenFileMissing(@TempDir Path dir) {
    final SecretsGateway gw = new TailscaleOauthClientGateway(dir.resolve("absent"));
    assertTrue(gw.read("tailscale").isEmpty());
  }

  @Test
  void emptyWhenNotAnOauthClientScalar(@TempDir Path dir) throws IOException {
    final Path client = dir.resolve("tailnet.tailscale.client");
    Files.writeString(client, "not-a-tskey-client");
    final SecretsGateway gw = new TailscaleOauthClientGateway(client);
    assertTrue(gw.read("tailscale").isEmpty());
  }

  @Test
  void chainReturnsFirstNonEmpty() {
    final SecretsGateway a =
        path -> path.equals("x") ? Optional.of("\"from-a\"") : Optional.empty();
    final SecretsGateway b = path -> Optional.of("\"from-b\"");
    final SecretsGateway chain = new ChainedSecretsGateway(List.of(a, b));

    assertEquals("\"from-a\"", chain.read("x").orElseThrow()); // a wins
    assertEquals("\"from-b\"", chain.read("y").orElseThrow()); // falls through to b
  }
}
