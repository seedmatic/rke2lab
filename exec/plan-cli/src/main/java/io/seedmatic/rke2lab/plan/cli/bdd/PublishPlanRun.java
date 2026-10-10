package io.seedmatic.rke2lab.plan.cli.bdd;

/**
 * The facts the plan CLI seeds into {@link PublishPlanScenario}: the {@code exportSoil} the netplan
 * and dataplan scions write their own export files into (a temp dir the CLI creates and deletes —
 * nothing reads it, it keeps a scion from leaving one behind), and the {@code worktreesRoot} the
 * fabric delivery makes its linked worktree of {@code fabric/plan} under ({@code
 * .local.d/worktrees} of the repository the CLI runs in).
 */
public record PublishPlanRun(String exportSoil, String worktreesRoot) {}
