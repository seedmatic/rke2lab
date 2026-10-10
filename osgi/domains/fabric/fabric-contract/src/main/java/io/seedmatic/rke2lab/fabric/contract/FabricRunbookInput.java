package io.seedmatic.rke2lab.fabric.contract;

import io.seedmatic.rke2lab.seed.broker.port.Amendment;
import io.seedmatic.rke2lab.seed.broker.port.SeedContract;
import java.util.Optional;

/**
 * The wire contract for the fabric {@code runbook} trigger — the activation payload a sower
 * supplies to play the delivery. The {@code shape} meta-coordinate projects THIS record's JSON
 * Schema so a sower learns the shape from the broker door rather than compiling the class.
 *
 * <p>It carries a SINGLE {@link Amendment}: {@link Amendment#SOIL} — {@link #worktreesRoot} is the
 * plot the linked worktree of {@link FabricDelivery#branch} is made under, which only the host
 * knows ({@code BootstrapPaths.worktreesRoot()}). The host fills it by role, never by field name.
 * {@link Optional#empty()} when unamended (a bare {@code shape} probe) → the scion works in a temp
 * directory; absence is an empty {@link Optional}, never a blank string.
 *
 * <p>Nothing else is carried, and that is the domain's shape: WHAT to deliver is not an input, it
 * is the two harvests the netplan and dataplan scions filed in this run's cellar. A fabric delivery
 * with no harvest to read is a broken run, not a run with an empty payload.
 */
@SeedContract("runbook")
public record FabricRunbookInput(@Amendment(Amendment.SOIL) Optional<String> worktreesRoot) {}
