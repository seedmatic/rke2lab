package io.seedmatic.rke2lab.seed.bdd.sow;

import com.fasterxml.jackson.databind.JsonNode;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.OsgiConnection;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import io.seedmatic.rke2lab.seed.broker.port.AmendCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.Cellar;
import io.seedmatic.rke2lab.seed.broker.port.RunbookCoordinate;
import io.seedmatic.rke2lab.seed.broker.port.SeedBroker;
import io.seedmatic.rke2lab.seed.broker.port.SeedEnvelope;
import java.util.Map;

/**
 * The open gardening — the framework as the gardener works it. An INSTANCE holding what it opened
 * (the live {@link OsgiConnection}) and the gardener it found there (the {@link SeedBroker}, "the
 * gardener realised as the SeedBroker"), and it knows how to SOW: hand it a soil name and it grows
 * that soil's scenario through the gardener, reaping the runbook. The driver {@link #open}s it
 * before playing, hands it to the scenario's GIVEN ("I have access to the open gardening"), and
 * {@link #close}s it after — so the gardening's whole lifecycle is one instance passed through the
 * call graph, never a static helper.
 *
 * <p>REALM-AGNOSTIC by design (why it lives in the exported {@code seed.bdd.sow} package): the
 * driver opens it host-side today (embedded), but a scion-peer could hold an open gardening
 * in-container tomorrow (remote) and sow another peer's runbook through the same door — the sowing
 * gesture is what the broker was built to make realm-indifferent. See
 * docs/architecture/osgi/seed-broker-spec.adoc and the gardening lexicon.
 */
public record Gardening(OsgiConnection connection, SeedBroker gardener) implements AutoCloseable {

  /** SCR publishes the gardener only after its handlers bind; wait a bounded while for it. */
  private static final long GARDENER_TIMEOUT_MILLIS = 30_000;

  /**
   * Open the gardening: boot the framework (embedded, from the staged bundles the exec-jar carries)
   * and find the gardener in it. The connection owns the framework's lifecycle, so {@link #close}
   * stops the world.
   */
  public static Gardening open() {
    return over(OsgiConnection.embedded());
  }

  /**
   * Open the gardening OVER an already-connected world — find the gardener in a connection someone
   * else owns (the world extension's, at the class scope). The gardening does NOT own this
   * connection's lifecycle: its {@link #close} only detaches (the connection reports {@code
   * ownsLifecycle}, its owner closes it). This is the host path once {@code ClusterSeedScenario}
   * wears a world discipline (the connection lives on the class store) — no second Felix booted.
   */
  public static Gardening over(OsgiConnection connection) {
    final SeedBroker gardener = connection.awaitService(SeedBroker.class, GARDENER_TIMEOUT_MILLIS);
    if (gardener == null) {
      throw new IllegalStateException(
          "no gardener in the open gardening within "
              + GARDENER_TIMEOUT_MILLIS
              + "ms — the broker runtime bundle (seed-broker-runtime) is not staged, or its "
              + "handlers never bound");
    }
    return new Gardening(connection, gardener);
  }

  /**
   * Sow {@code soil} with {@code amendments} and the ambient transaction {@code cellar} — a {@code
   * {role → value}} map the host holds under NEUTRAL {@link
   * io.seedmatic.rke2lab.seed.broker.port.Amendment} roles, each value a {@link JsonNode} so a role
   * may carry a flat scalar OR a sub-record (the incus {@code worktree} scalars). When non-empty, a
   * first sow at {@link AmendCoordinate} reconciles the roles onto the target's runbook input at
   * the DOOR — the host names no domain field — and the reconciled payload feeds the runbook sow;
   * when empty, the runbook is sown with the empty trigger (the scion falls back to its own
   * defaults). Only the {@code runbook} field of the reaped envelope is pulled, read generically.
   *
   * <p>The {@code cellar} IS the run's transaction (§ cellar-transactional): it rides BOTH sows so
   * the launched scion inherits its parent's txId + in-flight entries. The AMEND sow is upstream
   * introspection (a reflector ignores the cellar); the RUNBOOK sow plays the scion's transactional
   * scenario (its {@code *RunbookHandler} flattens the cellar into the in-container run).
   */
  public String sow(String soil, Map<String, JsonNode> amendments, Cellar cellar) {
    final RunbookCoordinate coordinate = new RunbookCoordinate(soil);
    final AmendCoordinate amend = new AmendCoordinate(soil);
    final SeedCodec codec = new SeedCodec();
    // Open the AMEND door when the host holds per-consult amendments OR a reflector serves this
    // soil — so a scion whose FACET is contributed AMBIENT (the manifests facet, the entry gate)
    // still
    // gets its door: the reflector runs on the empty trigger, applies its defaults, and gathers the
    // ambient roles. A soil with NO amend grower skips the door (a sow there would throw). The
    // amont introspection ignores the cellar; the runbook sow below plays the transactional scion.
    final SeedEnvelope trigger =
        amendments.isEmpty() && !gardener.serves(amend)
            ? SeedEnvelope.of(coordinate, "{}")
            : gardener.sow(
                amend, cellar, new SeedEnvelope(soil, coordinate.slug(), codec.encode(amendments)));
    final SeedEnvelope reaped = gardener.sow(coordinate, cellar, trigger);
    return codec.decode(reaped.payload()).path("runbook").asText();
  }

  /** Close the gardening — stop the framework the connection owns. */
  @Override
  public void close() {
    connection.close();
  }
}
