package io.seedmatic.rke2lab.plan.cli.bdd;

import com.fasterxml.jackson.databind.JsonNode;
import io.seedmatic.rke2lab.plan.cli.Plane;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The driver-captured facts the plan CLI seeds into {@link PlanCliScenario}: the {@link Plane} to
 * export (which names the coordinate the sow carries and the harvest it is read back from), the
 * plot the scion writes its own export file into (the {@code SOIL} amendment — a temp dir the CLI
 * creates and deletes, so a scion never leaves one behind; nothing reads it), and where the reaped
 * harvest is handed. {@link Optional#empty()} lets the scion fall to its own temp dir (a bare
 * survey), never a blank string.
 *
 * <p>{@code reaped} is how the tree leaves the run: the scenario reads the harvest from the run's
 * own cellar and hands it there, so the CLI renders what the scion DERIVED, not a file it wrote.
 */
public record PlanCliRun(
    Plane plane, Optional<String> materializationRoot, Consumer<JsonNode> reaped) {}
