package io.seedmatic.rke2lab.plan.cli.bdd;

/**
 * The facts the plan CLI seeds into {@link PublishPlanScenario}: the {@code exportSoil} the netplan
 * and dataplan scions write their own export files into (a temp dir the CLI creates and deletes —
 * nothing reads it, it keeps a scion from leaving one behind). The fabric delivery needs no plot:
 * it commits onto {@code fabric/plan}'s head in memory.
 */
public record PublishPlanRun(String exportSoil) {}
