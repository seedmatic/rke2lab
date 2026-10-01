package io.seedmatic.rke2lab.controlplane.bdd;

import io.seedmatic.rke2lab.incus.ingress.Growth;
import io.seedmatic.rke2lab.pulumi.edge.StackSnapshot;
import java.util.List;

/**
 * Reads the cold/warm {@link Growth} of a grow off the run's prior stack snapshot — the host-side
 * derivation of the fact the grow then freezes on the transient bus as {@code GrowOutcome}. WARM
 * iff the {@code seed-instance} resource was already observed {@code Running} in the snapshot; COLD
 * when it is absent (a fresh bring-up) or present but not running. The read is the truth ONLY at
 * grow entry — the grow sets the instance running, so a later read of the mutated state would
 * always say WARM; hence the fact is captured here, once, and carried forward.
 *
 * <p>Host-only (it reads a Pulumi {@link StackSnapshot}); the produced {@link Growth} crosses into
 * the OSGi realm as the dual-realm {@code GrowOutcome} fact, not this reader.
 */
public record GrowthCondition(StackSnapshot snapshot) {

  // The Pulumi type token the incus provider mints for an instance resource — the read side of the
  // token InstanceGrow's `new Instance(...)` declares (Pulumi generates it, there is no shared
  // constant to reference).
  private static final String INSTANCE_TYPE_TOKEN = "incus:index/instance:Instance";

  public Growth growth() {
    final boolean runningNow =
        snapshot.outputsOfType(INSTANCE_TYPE_TOKEN).getOrDefault("status", List.of()).stream()
            .anyMatch("Running"::equals);
    return runningNow ? Growth.WARM : Growth.COLD;
  }
}
