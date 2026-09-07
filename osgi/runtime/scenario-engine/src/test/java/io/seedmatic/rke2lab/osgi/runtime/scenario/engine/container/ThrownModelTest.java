package io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Proves the failure travels as a STRUCTURED POJO through the mapper — its frames and cause chain
 * are captured from the LIVE {@link Throwable} and rebuilt host-side with NO printStackTrace
 * re-parse (the defect this replaced: frames reconstructed from a printed dump).
 */
class ThrownModelTest {

  private final SeedCodec codec = new SeedCodec();

  @Test
  void capturesFramesAndTheCauseChainFromTheLiveThrowable() {
    final Throwable root = new IllegalStateException("connection refused");
    final Throwable top = new RuntimeException("not ready", root);

    final ThrownModel model = ThrownModel.of(top);

    assertEquals(RuntimeException.class.getName(), model.type());
    assertEquals(Optional.of("not ready"), model.message());
    assertTrue(model.frames().size() > 0, "the live frames are captured, not parsed from a string");
    assertTrue(model.cause().isPresent(), "the cause chain is captured");
    assertEquals(IllegalStateException.class.getName(), model.cause().orElseThrow().type());
    assertEquals(Optional.of("connection refused"), model.cause().orElseThrow().message());
    assertTrue(model.cause().orElseThrow().cause().isEmpty(), "the chain ends at the root");
  }

  @Test
  void roundTripsThroughTheCodecAndRebuildsARealThrowableWithItsCause() {
    final Throwable top =
        new RuntimeException("not ready", new IllegalStateException("connection refused"));
    final ThrownModel captured = ThrownModel.of(top);

    final ThrownModel back = codec.decode(codec.encode(captured), ThrownModel.class);

    assertEquals(captured, back, "the structured failure round-trips verbatim through the mapper");
    final Throwable rebuilt = back.toThrowable();
    assertEquals("not ready", rebuilt.getMessage());
    assertNotNull(rebuilt.getCause());
    assertEquals("connection refused", rebuilt.getCause().getMessage());
    assertTrue(
        rebuilt.getStackTrace().length > 0,
        "the rebuilt throwable carries real StackTraceElements");
  }
}
