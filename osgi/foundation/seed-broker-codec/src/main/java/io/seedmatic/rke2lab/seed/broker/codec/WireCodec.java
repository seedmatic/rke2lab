package io.seedmatic.rke2lab.seed.broker.codec;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * The {@link SeedCodec#wire()} profile: the bodies exchanged with external APIs (GitHub,
 * Tailscale). The peer dictates the shape, so nothing is reordered or dropped — a key the peer may
 * expect stays, even when null — and the body is compact JSON. A field the peer adds tomorrow is
 * ignored, not a crash.
 */
public final class WireCodec extends ReadingCodec {

  WireCodec(ReadingCodec reading) {
    super(reading.json(), reading.yaml());
  }

  public String writeJson(Object value) {
    try {
      return json().writeValueAsString(value);
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed to write wire JSON", ex);
    }
  }
}
