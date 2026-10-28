package io.seedmatic.rke2lab.manifests.bdd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.seedmatic.rke2lab.manifests.contract.profiles.ReplicatorSourceSecretsMaterial.SourceSecret;
import io.seedmatic.rke2lab.seed.broker.port.SecretsGateway;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The replicator-secrets seal, against an in-memory {@code .secrets}: which source Secrets it
 * assembles from which blocks. No cellar, no OSGi: the WHEN stage alone.
 */
class ReplicatorSecretsSealTest {

  private static SecretsGateway secrets(final Map<String, String> blocks) {
    return path -> Optional.ofNullable(blocks.get(path));
  }

  private static final String KUBERNETES =
      "{\"sourceNamespace\":\"rke2lab-secrets\",\"secrets\":{}}";

  private static List<SourceSecret> assemble(final Map<String, String> blocks) {
    final ReplicatorSecretsSealScenario.When when = new ReplicatorSecretsSealScenario.When();
    when.the_replicator_source_secrets_are_resolved(Optional.of(secrets(blocks)));
    return when.material.sources();
  }

  // The FloxHub token resolves the FloxHub-catalog packages and relocks on the nodes: one Secret in
  // the source namespace, replicated to rke2lab-system where flox-controller reads it.
  @Test
  void aFloxhubTokenBlockBecomesItsSourceSecret() {
    final List<SourceSecret> sources =
        assemble(Map.of("kubernetes", KUBERNETES, "floxhub", "{\"token\":\"t0k\"}"));
    assertEquals(1, sources.size(), "sources: " + sources);
    final SourceSecret token = sources.get(0);
    assertEquals("floxhub-token", token.name());
    assertEquals("rke2lab-secrets", token.namespace());
    assertEquals(Map.of("token", "t0k"), token.stringData());
    assertEquals(List.of("rke2lab-system"), token.replicationAllowedNamespaces());
  }

  // No block, no Secret: a cluster without the token still runs its catalog-only envs.
  @Test
  void noFloxhubBlockNoSecret() {
    assertFalse(
        assemble(Map.of("kubernetes", KUBERNETES)).stream()
            .anyMatch(s -> s.name().equals("floxhub-token")));
  }
}
