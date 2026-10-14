package io.seedmatic.rke2lab.fabric.contract;

import io.seedmatic.rke2lab.seed.broker.port.SeedContract;

/**
 * The wire contract for the fabric {@code runbook} trigger — the activation payload a sower
 * supplies to play the delivery. The {@code shape} meta-coordinate projects THIS record's JSON
 * Schema so a sower learns the shape from the broker door rather than compiling the class.
 *
 * <p>It carries NOTHING, and that is the domain's shape: the delivery commits onto the {@link
 * FabricDelivery#branch} head in memory, so it needs no plot on disk; WHAT to deliver is not an
 * input, it is the two harvests the netplan and dataplan scions filed in this run's cellar. A
 * fabric delivery with no harvest to read is a broken run, not a run with an empty payload.
 */
@SeedContract("runbook")
public record FabricRunbookInput() {}
