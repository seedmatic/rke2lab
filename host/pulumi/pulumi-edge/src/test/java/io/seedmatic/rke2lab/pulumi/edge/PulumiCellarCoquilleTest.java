package io.seedmatic.rke2lab.pulumi.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.pulumi.edge.PulumiCellar.Shelved;
import io.seedmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.seedmatic.rke2lab.seed.broker.port.SourceCrumb;
import io.seedmatic.rke2lab.seed.broker.port.Trail;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The durable COQUILLE characterisation — the shell/unshell pair that carries an envelope across
 * the Pulumi backend as five CLEAR fields {@code {domain, trail, tombstone, mac, payload}} around
 * the opaque payload (§ fil-d-ariane, the durable extension). Proves what the old {@code
 * (coordinate → payload)} shell dropped now SURVIVES the round-trip — domain, the fil d'Ariane, and
 * a first-class tombstone — and that the general MAC (decision A) binds the clear layer to the
 * payload: any tamper of either fails closed. Exercises the codec directly (no live Pulumi {@code
 * up()}); the plumbing around it — {@code writeShell}/{@code reap} — is unchanged.
 */
class PulumiCellarCoquilleTest {

  private static final String COORDINATE = "incus-facts";

  // No backend: the coquille codec (shell/unshell) never touches the file backend — it is pure.
  private final PulumiCellar cellar = new PulumiCellar(Optional.empty(), () -> false, msg -> {});

  private static Trail sampleTrail() {
    // A two-crumb fil d'Ariane (root → here) — proves a multi-element trail survives, not just one.
    return new Trail(
        List.of(
            new SourceCrumb("worktree", "run-provenance", "abc123", true),
            new SourceCrumb("incus", COORDINATE, "abc123", true)));
  }

  @Test
  void aClearValueRoundTripsWithItsDomainAndTrailAndTombstoneFalse() {
    final Trail trail = sampleTrail();
    final Map<String, Object> shell =
        cellar.shell("incus", COORDINATE, trail, false, "{\"box\":\"nikopol\"}");

    final Shelved shelved = cellar.unshell(COORDINATE, shell);
    final SeedEnvelope restored = shelved.envelope();

    assertEquals("incus", restored.domain(), "the domain survives the durable round-trip");
    assertEquals(COORDINATE, restored.coordinate());
    assertEquals("{\"box\":\"nikopol\"}", restored.payload());
    assertEquals(trail, restored.trail(), "the fil d'Ariane survives clear, full chain preserved");
    assertFalse(shelved.tombstone(), "a store is not a tombstone");
  }

  @Test
  void aSealedPayloadRidesOpaqueWhileItsTrailStaysClear() {
    // The host never opens the payload — a sealed value is just an opaque string field. Its trail
    // stays readable clear alongside: a secured value's lineage is traceable without the
    // passphrase.
    final String sealed = "cellar:sealed:v1:c2VhbGVkLWJsb2I=";
    final Trail trail = sampleTrail();
    final Map<String, Object> shell = cellar.shell("incus", COORDINATE, trail, false, sealed);

    final Shelved shelved = cellar.unshell(COORDINATE, shell);

    assertEquals(
        sealed, shelved.envelope().payload(), "the sealed payload rides verbatim, unopened");
    assertEquals(trail, shelved.envelope().trail(), "the sealed value's lineage is readable clear");
  }

  @Test
  void aTombstoneCoquilleRoundTripsAsFirstClass() {
    final Map<String, Object> shell = cellar.shell("", COORDINATE, Trail.empty(), true, "");

    final Shelved shelved = cellar.unshell(COORDINATE, shell);

    assertTrue(
        shelved.tombstone(), "the tombstone is a first-class shell flag, not an in-band marker");
  }

  @Test
  void tamperingTheClearShellFailsTheMac() {
    final Map<String, Object> shell = cellar.shell("incus", COORDINATE, sampleTrail(), false, "{}");
    // Rewrite the domain UNDER the same MAC — the sops move the binding exists to catch.
    shell.put("domain", "attacker");

    assertThrows(
        SecurityException.class,
        () -> cellar.unshell(COORDINATE, shell),
        "a tampered clear shell fails the MAC and closes");
  }

  @Test
  void tamperingThePayloadUnderTheSameTrailFailsTheMac() {
    final Map<String, Object> shell =
        cellar.shell("incus", COORDINATE, sampleTrail(), false, "{\"box\":\"nikopol\"}");
    // Swap the payload while leaving the trail intact — the exact "reveal notices nothing" attack.
    shell.put("payload", "{\"box\":\"attacker\"}");

    assertThrows(
        SecurityException.class,
        () -> cellar.unshell(COORDINATE, shell),
        "swapping the payload under the same trail fails the MAC");
  }

  @Test
  void movingACoquilleToAnotherCaseFailsTheMac() {
    // The coordinate (the output name) is part of the bound characterisation.
    final Map<String, Object> shell = cellar.shell("incus", COORDINATE, sampleTrail(), false, "{}");

    // ⚠️ This name MUST differ from COORDINATE — the whole assertion is that a coquille does not
    // survive being read under another one. A rename that collapsed the two would turn this into a
    // tautology that passes for the wrong reason (and did, once).
    assertThrows(
        SecurityException.class,
        () -> cellar.unshell("cluster-facts", shell),
        "a coquille read under a different coordinate fails the MAC");
  }
}
