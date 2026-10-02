package io.seedmatic.rke2lab.controlplane.config;

import java.util.List;

/**
 * Thrown at configuration load time when one or more mandatory keys are absent or blank. Keys are
 * accumulated during one load pass and reported together, so the operator fixes everything in a
 * single edit. The message names each key with its {@code rke2lab:} prefix for direct paste into
 * Pulumi.&lt;stack&gt;.yaml.
 */
public final class MissingRequiredConfiguration extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient List<String> keys;

  public MissingRequiredConfiguration(List<String> keys) {
    super(buildMessage(keys));
    this.keys = List.copyOf(keys);
  }

  public List<String> keys() {
    return keys;
  }

  private static String buildMessage(List<String> keys) {
    final StringBuilder message =
        new StringBuilder("Missing required configuration (add to Pulumi.<stack>.yaml):");
    for (String key : keys) {
      message.append("\n  - rke2lab:").append(key);
    }
    return message.toString();
  }
}
