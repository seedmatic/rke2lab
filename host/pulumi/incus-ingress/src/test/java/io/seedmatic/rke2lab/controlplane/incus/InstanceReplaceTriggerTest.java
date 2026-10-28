package io.seedmatic.rke2lab.controlplane.incus;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pulumi.core.Output;
import com.pulumi.resources.CustomResourceOptions;
import org.junit.jupiter.api.Test;

/**
 * A new image replaces the seed instance. Measured on 2026-10-28: a grow moved the watched config
 * key (user.rke2lab.imageBuildChecksum, 19b144e5 → 95005036) and wrote the new fingerprint, yet the
 * master kept its old base image, because the provider applied a {@code replaceOnChanges} on a
 * config map key as an in-place update. The replace now rides the engine's own {@code
 * replacementTrigger}, on the image fingerprint.
 */
class InstanceReplaceTriggerTest {

  @Test
  void the_image_fingerprint_triggers_the_replace() {
    final Output<String> fingerprint = Output.of("a".repeat(64));
    final CustomResourceOptions options = InstanceGrow.instanceOptions(null, fingerprint);
    assertTrue(
        options.getReplacementTrigger().isPresent(),
        "no replacementTrigger: a new image would update the instance in place");
    assertSame(fingerprint, options.getReplacementTrigger().orElseThrow());
  }

  @Test
  void the_instance_is_deleted_before_it_is_replaced() {
    // One instance name per node: the new one cannot be created while the old one holds it.
    assertTrue(
        InstanceGrow.instanceOptions(null, Output.of("b".repeat(64))).getDeleteBeforeReplace());
  }
}
