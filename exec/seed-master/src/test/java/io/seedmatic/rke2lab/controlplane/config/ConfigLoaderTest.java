package io.seedmatic.rke2lab.controlplane.config;

import static io.seedmatic.rke2lab.controlplane.config.OperatorConfiguration.loaderOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConfigLoaderTest {

  @Test
  void optional_empty_when_section_or_key_absent() {
    final ConfigLoader loader = loaderOf(Map.of());
    assertTrue(loader.optional("incus", "project").isEmpty());
    assertTrue(loader.optionalPath("kubeconfig", "ref").isEmpty());
  }

  @Test
  void optional_returns_present_value() {
    final ConfigLoader loader = loaderOf(Map.of("incus", Map.of("project", "rke2lab")));
    assertEquals(Optional.of("rke2lab"), loader.optional("incus", "project"));
  }

  @Test
  void require_accumulates_without_throwing_mid_load() {
    final ConfigLoader loader = loaderOf(Map.of());
    loader.requirePath("incus", "configDir");
    loader.requirePath("incus", "remoteAddress");
    assertEquals(List.of("incus.configDir", "incus.remoteAddress"), loader.missingKeys());
  }

  @Test
  void diagnose_throws_with_all_keys() {
    final ConfigLoader loader = loaderOf(Map.of());
    loader.requirePath("incus", "configDir");
    loader.requirePath("incus", "remoteAddress");
    final MissingRequiredConfiguration ex =
        assertThrows(MissingRequiredConfiguration.class, loader::diagnoseIfIncomplete);
    assertEquals(List.of("incus.configDir", "incus.remoteAddress"), ex.keys());
  }

  @Test
  void dotted_section_walks_into_submap() {
    final ConfigLoader loader =
        ConfigLoader.ofNestedRoot(Map.of("manifests", Map.of("publish", Map.of("gitops", "true"))));
    assertEquals(Optional.of(true), loader.optionalBoolean("manifests.publish", "gitops"));
  }

  // --- bind(): a record's @SecretJoin deep-merges the named .secrets subtree before mapping ---

  @Test
  void bind_merges_the_secret_subtree_named_by_the_records_secret_join() {
    final ConfigLoader loader =
        ConfigLoader.ofNestedRoots(
            Map.of("joined", Map.of("reconcile", Map.of("failOnError", "true"))),
            Map.of(
                "lan",
                Map.of("router", Map.of("uri", "https://example.invalid", "password", "s3cr3t"))));
    final JoinedConfig joined = loader.bind(JoinedConfig.class, "joined");
    // The typed input is mapped...
    assertEquals(Optional.of(true), joined.reconcile().failOnError());
    // ...the contact the host owns lands blind in the remainder (never named as fields)...
    assertEquals("https://example.invalid", joined.rest().get("uri"));
    assertEquals("s3cr3t", joined.rest().get("password"));
    // ...and the facet re-serialises the whole payload (reconcile rides along, no join meta).
    final String facet = joined.facetJson();
    assertTrue(facet.contains("password"));
    assertTrue(facet.contains("reconcile"));
    assertTrue(!facet.contains("\"from\""));
  }

  @Test
  void bind_secret_leaf_wins_on_collision_with_config() {
    final ConfigLoader loader =
        ConfigLoader.ofNestedRoots(
            Map.of("joined", Map.of("uri", "https://placeholder")),
            Map.of("lan", Map.of("router", Map.of("uri", "https://example.invalid"))));
    final JoinedConfig joined = loader.bind(JoinedConfig.class, "joined");
    assertEquals("https://example.invalid", joined.rest().get("uri"));
  }

  @Test
  void bind_pulls_no_secret_for_a_record_without_a_secret_join() {
    final ConfigLoader loader =
        ConfigLoader.ofNestedRoots(
            Map.of("manifests", Map.of("publish", Map.of("gitops", "true"))),
            Map.of("lan", Map.of("router", Map.of("password", "s3cr3t"))));
    final Rke2labConfig.ManifestsConfig manifests =
        loader.bind(Rke2labConfig.ManifestsConfig.class, "manifests");
    // The manifests subtree is carried blind...
    assertTrue(manifests.rest().containsKey("publish"));
    // ...and with no @SecretJoin the secrets document contributes nothing.
    assertTrue(!manifests.facetJson().contains("password"));
  }

  /**
   * A test-local coordinate carrying a {@link SecretJoin} — the one fixture these two tests need.
   *
   * <p>It used to be a production domain record (the only one that ever carried the annotation), so
   * a test of {@link ConfigLoader}'s deep-merge died with that domain when it was excised. The
   * mechanism under test belongs to the config layer, so its fixture does too.
   */
  @SecretJoin(from = "lan.router")
  record JoinedConfig(ReconcileConfig reconcile, @JsonAnySetter Map<String, Object> rest)
      implements Facet {

    JoinedConfig {
      reconcile = reconcile == null ? new ReconcileConfig(Optional.empty()) : reconcile;
      rest = rest == null ? Map.of() : rest;
    }

    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    record ReconcileConfig(Optional<Boolean> failOnError) {}

    @JsonAnyGetter
    public Map<String, Object> rest() {
      return rest;
    }

    @Override
    public String facetJson() {
      return ConfigLoader.writeJson(this);
    }
  }
}
