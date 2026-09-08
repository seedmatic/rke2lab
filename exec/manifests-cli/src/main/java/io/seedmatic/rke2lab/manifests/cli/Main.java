// @codebase
package io.seedmatic.rke2lab.manifests.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tngtech.jgiven.report.model.ExecutionStatus;
import com.tngtech.jgiven.report.model.ReportModel;
import com.tngtech.jgiven.report.text.PlainTextScenarioWriter;
import io.seedmatic.rke2lab.manifests.cli.bdd.ManifestsCliRun;
import io.seedmatic.rke2lab.manifests.cli.bdd.ManifestsCliScenario;
import io.seedmatic.rke2lab.manifests.cli.bdd.PublishCliScenario;
import io.seedmatic.rke2lab.manifests.cli.bdd.VersionsCliRun;
import io.seedmatic.rke2lab.manifests.cli.bdd.VersionsCliScenario;
import io.seedmatic.rke2lab.manifests.ingress.BumpLevel;
import io.seedmatic.rke2lab.manifests.ingress.Component;
import io.seedmatic.rke2lab.osgi.runtime.junit.launcher.JUnitLauncherCore;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.LogFileSeed;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.RunRole;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.RunRoleSeed;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.ScenarioOutcomeSeed;
import io.seedmatic.rke2lab.osgi.runtime.scenario.engine.container.TxIdSeed;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.engine.JupiterTestEngine;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The manifests CLI north-adapter. The {@code synthesize} verb drives {@link ManifestsCliScenario}
 * on the embedded JUnit launcher — the SAME BDD-as-engine machinery {@code seed-master} uses, minus
 * the Pulumi envelope. The scenario opens the gardening and sows the {@code manifests} coordinate
 * through the broker; that grows {@code ManifestSynthesisScenario} in-container, where its
 * {@code @OsgiService ManifestSynthesisService} resolves bundle-side.
 *
 * <p>It does NOT {@code awaitService(ManifestSynthesisService.class)} from the host: that class
 * lives in the non-seam {@code manifests-contract} bundle, so the flat host copy never matches the
 * bundle-registered service (the old boot+awaitService CLI was structurally broken). The host
 * speaks ONLY the {@code seed.broker.port} membrane — the broker sow — never a typed domain
 * service.
 */
public final class Main {

  private final Logger logger = LoggerFactory.getLogger(Main.class);

  private Main() {}

  /**
   * The delivery verbs — the host's OWN copy of the render-mode vocabulary. The name crosses the
   * broker membrane as the {@code render-mode} amendment's {@code verb} string, matched against
   * {@code manifests.contract.RenderMode.Verb} BY NAME (the host stays membrane-blind, never
   * compiling against the domain contract). {@code GROW} is the survey default; {@code INIT}/{@code
   * UPDATE}/{@code EDIT} are the CLI delivery verbs.
   */
  private enum DeliverVerb {
    GROW,
    INIT,
    UPDATE,
    EDIT
  }

  public static void main(String[] args) throws IOException {
    try {
      new Main().execute(args);
    } catch (UncheckedIOException ex) {
      throw ex.getCause();
    }
  }

  void execute(String[] args) {
    resolveCommand(args).run();
  }

  private CliCommand resolveCommand(String[] args) {
    if (args.length == 0) {
      return commandOf(new HelpCommand.Builder(this).commands(availableCommands()));
    }

    final String command = args[0];
    switch (command) {
      case "help", "--help", "-h" -> {
        return commandOf(new HelpCommand.Builder(this).commands(availableCommands()));
      }
      case "synthesize" -> {
        final Map<String, String> options = optionArgs(args);
        return commandOf(new SynthesizeCommand.Builder(this).run(run(options, false, growMode())));
      }
      case "init", "update", "edit" -> {
        final DeliverVerb verb = DeliverVerb.valueOf(command.toUpperCase(Locale.ROOT));
        final Map<String, String> options = optionArgs(args);
        return commandOf(
            new DeliverCommand.Builder(this)
                .verb(verb)
                .run(run(options, true, renderMode(verb, options))));
      }
      case "versions" -> {
        final Map<String, String> options = optionArgs(args);
        return commandOf(
            new VersionsCommand.Builder(this)
                .level(option(options, "level").orElse("minor"))
                .apply(option(options, "apply").map(Boolean::parseBoolean).orElse(false))
                .component(option(options, "component")));
      }
      default ->
          throw new IllegalArgumentException(
              "Unknown command: " + command + ". Run with 'help' for available commands.");
    }
  }

  private List<CliCommand> availableCommands() {
    return List.of(
        commandOf(new SynthesizeCommand.Builder(this)),
        commandOf(new DeliverCommand.Builder(this).verb(DeliverVerb.INIT)),
        commandOf(new DeliverCommand.Builder(this).verb(DeliverVerb.UPDATE)),
        commandOf(new DeliverCommand.Builder(this).verb(DeliverVerb.EDIT)),
        commandOf(new VersionsCommand.Builder(this)),
        commandOf(new HelpCommand.Builder(this).commands(List.of())));
  }

  /**
   * Parse the verb's trailing {@code key=value} arguments (everything after {@code args[0]}) into a
   * map, so {@code versions apply=true level=major} or {@code publish cluster=… node=…} work as
   * typed. Every verb takes its inputs here — there are no system properties or environment reads.
   */
  private Map<String, String> optionArgs(String[] args) {
    final Map<String, String> options = new java.util.LinkedHashMap<>();
    for (int i = 1; i < args.length; i++) {
      final int eq = args[i].indexOf('=');
      if (eq > 0) {
        options.put(args[i].substring(0, eq).trim(), args[i].substring(eq + 1).trim());
      }
    }
    return options;
  }

  /** A trailing {@code key=value} arg, trimmed; empty when absent or blank. */
  private Optional<String> option(Map<String, String> options, String key) {
    return Optional.ofNullable(options.get(key))
        .map(String::trim)
        .filter(value -> !value.isEmpty());
  }

  /**
   * The facts the CLI seeds so it sows a COMPLETE manifests runbook input: the {@code IDENTITY}
   * ({@code cluster=}/{@code node=}), the mandatory {@code FACET} the CLI builds itself (the sower
   * honours the contract, no door default), and — for {@code publish} only — the {@code SOIL}, the
   * render worktree the exe LOCATES itself from the cluster.
   *
   * <p>The cluster names the branch {@code manifests/<cluster>}; it is the SINGLE SOURCE OF TRUTH
   * the branch is cut from and INLINES the host ({@code <host>-mgmt}). It defaults to this host's
   * mgmt coordinate ({@code <short-host>-mgmt}, the standalone hostname derivation) — in-cluster
   * the render pipeline passes it inline from the per-cluster PaC {@code Repository} CR; {@code
   * node} defaults to the single management node. Override {@code cluster=}/{@code node=} for
   * another coordinate.
   *
   * <p>Only {@code publish} works in the branch: it prepares a linked worktree on {@code
   * manifests/<cluster>} at {@code .local.d/render/<cluster>} (the exe locates it — never passed
   * in), follows its HEAD facet, and delivers (signed commit + ff-push). {@code synthesize} is pure
   * generation — it does not know jgit — so it surveys into a temp dir (empty {@code SOIL}: no
   * branch, no commit).
   */
  private ManifestsCliRun run(Map<String, String> options, boolean armPush, JsonNode renderMode) {
    final String cluster = option(options, "cluster").orElseGet(Main::defaultCluster);
    final ManifestsCliRun.Identity identity =
        new ManifestsCliRun.Identity(cluster, option(options, "node").orElse("master"));
    final Optional<String> worktree =
        armPush ? Optional.of(".local.d/render/" + cluster) : Optional.empty();
    return ManifestsCliRun.of(
        worktree, Optional.of(identity), manifestsFacet(options, armPush), renderMode);
  }

  // The facet toggles the CLI accepts, mapped to their dotted JSON path in the recorded facet:
  // debug.* wrap the boolean in {enabled}, so the edit overlay path appends it. Which manifest
  // domains render is no longer a toggle — it follows the cluster ROLE (see ClusterRole).
  private static final Map<String, String> FACET_TOGGLE_PATHS =
      Map.ofEntries(
          Map.entry("debug.mesh", "debug.mesh.enabled"),
          Map.entry("debug.networking", "debug.networking.enabled"),
          Map.entry("debug.nriPlugins.flox", "debug.nriPlugins.flox.enabled"));

  /** The default render mode a survey (synthesize) carries — the scion's GROW: the seeded facet. */
  private ObjectNode growMode() {
    return verbNode(DeliverVerb.GROW);
  }

  private ObjectNode verbNode(DeliverVerb verb) {
    return JsonNodeFactory.instance.objectNode().put("verb", verb.name());
  }

  /**
   * The render-mode amendment JSON a delivery verb sows. {@code init}/{@code update} carry only the
   * verb; {@code edit} also carries the SPARSE overrides — the facet toggles the operator
   * explicitly set, as dotted JSON paths into the recorded facet ({@code debug.mesh}, {@code
   * debug.networking.enabled}) → boolean, which the synthesis overlays on the branch HEAD. Fails
   * loud on an unknown toggle or an {@code edit} that changes nothing.
   */
  private ObjectNode renderMode(DeliverVerb verb, Map<String, String> options) {
    final ObjectNode mode = verbNode(verb);
    if (verb != DeliverVerb.EDIT) {
      return mode;
    }
    final ObjectNode overrides = mode.putObject("overrides");
    options.forEach(
        (key, value) -> {
          if (key.equals("cluster") || key.equals("node")) {
            return;
          }
          final String path = FACET_TOGGLE_PATHS.get(key);
          if (path == null) {
            throw new IllegalArgumentException(
                "unknown facet toggle for edit: '"
                    + key
                    + "' (valid: "
                    + FACET_TOGGLE_PATHS.keySet()
                    + ")");
          }
          overrides.put(path, Boolean.parseBoolean(value));
        });
    if (overrides.isEmpty()) {
      throw new IllegalArgumentException(
          "edit needs at least one facet toggle to change, e.g. debug.mesh=true");
    }
    return mode;
  }

  /**
   * The default cluster: {@code <short-host>-mgmt} (e.g. {@code bioskop} → {@code bioskop-mgmt}).
   */
  private static String defaultCluster() {
    String host = System.getenv("HOSTNAME");
    if (host == null || host.isBlank()) {
      try {
        host = java.net.InetAddress.getLocalHost().getHostName();
      } catch (java.net.UnknownHostException ex) {
        throw new IllegalArgumentException(
            "cannot resolve the host for the default cluster; pass cluster=<name>", ex);
      }
    }
    final int dot = host.indexOf('.');
    return (dot < 0 ? host : host.substring(0, dot)) + "-mgmt";
  }

  /**
   * The manifests {@code FACET} the CLI sows — the {@code {debug, delivery}} concern the seed flow
   * reads from Pulumi, built here instead. {@code debug} defaults off; each toggle is overridable
   * via a trailing {@code debug.<toggle>=…} arg. Which manifest domains render is NOT here — it
   * follows the cluster ROLE (see {@code ClusterRole}), derived from the identity. When {@code
   * armPush} is set the {@code delivery.push=true} intent is added — the git-push gate {@code
   * ManifestSynthesisScenario} reads; the render scenario keeps this delivery when it follows the
   * branch HEAD's recorded facet. The shape mirrors {@code ManifestsRunbookInput.Facets} — the
   * membrane carries only JSON.
   */
  private ObjectNode manifestsFacet(Map<String, String> options, boolean armPush) {
    final ObjectNode facet = JsonNodeFactory.instance.objectNode();
    final ObjectNode debug = facet.putObject("debug");
    debug.putObject("mesh").put("enabled", toggle(options, "debug.mesh", false));
    debug.putObject("networking").put("enabled", toggle(options, "debug.networking", false));
    debug
        .putObject("nriPlugins")
        .putObject("flox")
        .put("enabled", toggle(options, "debug.nriPlugins.flox", false));
    if (armPush) {
      facet.putObject("delivery").put("push", true);
    }
    return facet;
  }

  private boolean toggle(Map<String, String> options, String key, boolean fallback) {
    return options.containsKey(key) ? Boolean.parseBoolean(options.get(key)) : fallback;
  }

  private final class HelpCommand implements CliCommand {

    private final List<CliCommand> commands;

    @SuppressWarnings("unused")
    HelpCommand(Builder builder) {
      this.commands = builder.commands;
    }

    @Override
    public String name() {
      return "help";
    }

    @Override
    public String description() {
      return "Show available commands and usage";
    }

    @Override
    public String usage() {
      return "help";
    }

    @Override
    public void run() {
      logger.info("Usage: rke2lab-manifests <command> [args]");
      logger.info("Available commands:");
      for (CliCommand command : commands) {
        logger.info("{}", String.format("  %-24s %s", command.usage(), command.description()));
      }
    }

    static final class Builder implements CommandBuilder<HelpCommand> {
      private final Main main;

      private List<CliCommand> commands = List.of();

      Builder(Main main) {
        this.main = main;
      }

      Builder commands(List<CliCommand> commands) {
        this.commands = List.copyOf(commands);
        return this;
      }

      public HelpCommand build() {
        return main.commandOf(this);
      }

      public Class<HelpCommand> commandClass() {
        return HelpCommand.class;
      }
    }
  }

  private final class SynthesizeCommand implements CliCommand {

    final ManifestsCliRun run;

    @SuppressWarnings("unused")
    SynthesizeCommand(Builder builder) {
      this.run = builder.run;
    }

    @Override
    public String name() {
      return "synthesize";
    }

    @Override
    public String description() {
      return "Synthesize manifests by sowing the manifests coordinate through the broker";
    }

    @Override
    public String usage() {
      return "synthesize [cluster=<host>-mgmt] [node=master] [debug.<x>=bool]";
    }

    @Override
    public void run() {
      // Drive the host-side ManifestsCliScenario on the embedded launcher — the SAME BDD engine
      // seed-master uses. The scenario opens the gardening (boots the staged bundles + resolves the
      // broker) and sows the manifests coordinate; the broker grows ManifestSynthesisScenario
      // in-container, materialising into the SOIL this run carries. No host-typed awaitService.
      final String txId = UUID.randomUUID().toString();
      try {
        final ReportModel runbook =
            new JUnitLauncherCore<ReportModel>()
                .run(
                    Main.class.getClassLoader(),
                    JupiterTestEngine.class,
                    wiring -> List.of(DiscoverySelectors.selectClass(ManifestsCliScenario.class)),
                    (launcher, request, sessionStore) -> {
                      final SummaryGeneratingListener listener = new SummaryGeneratingListener();
                      launcher.execute(request, listener);
                      final var summary = listener.getSummary();
                      if (summary.getTotalFailureCount() > 0) {
                        final var first = summary.getFailures().get(0);
                        throw new IllegalStateException(
                            "the manifests-cli scenario failed: "
                                + first.getTestIdentifier().getDisplayName(),
                            first.getException());
                      }
                      return new ScenarioOutcomeSeed().read(sessionStore).runbook();
                    },
                    ManifestsCliScenario.SEED
                        .into(run)
                        .andThen(RunRoleSeed.into(RunRole.ROOT))
                        .andThen(TxIdSeed.into(txId))
                        .andThen(LogFileSeed.into(".local.d/manifests-cli.log")));
        final List<?> broken =
            runbook.getScenariosWithStatus(ExecutionStatus.FAILED, ExecutionStatus.ABORTED);
        if (!broken.isEmpty()) {
          throw new IllegalStateException(
              "manifest synthesis did not complete (" + broken.size() + " failed/aborted)");
        }
        run.materializationRoot()
            .ifPresentOrElse(
                root -> logger.info("Manifests synthesized into {}", root),
                () -> logger.info("Manifests synthesized into a temporary survey directory"));
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("the manifests-cli run was interrupted", interrupted);
      }
    }

    static final class Builder implements CommandBuilder<SynthesizeCommand> {
      private final Main main;

      private ManifestsCliRun run;

      Builder(Main main) {
        this.main = main;
        this.run =
            ManifestsCliRun.of(
                Optional.empty(),
                Optional.empty(),
                main.manifestsFacet(Map.of(), false),
                main.growMode());
      }

      Builder run(ManifestsCliRun run) {
        this.run = run;
        return this;
      }

      public SynthesizeCommand build() {
        return main.commandOf(this);
      }

      public Class<SynthesizeCommand> commandClass() {
        return SynthesizeCommand.class;
      }
    }
  }

  /**
   * The delivery verbs — {@code init}/{@code update}/{@code edit} — one command parameterised by
   * the {@link DeliverVerb} it carries. All three drive {@link PublishCliScenario} (render into the
   * plot + signed commit + push {@code manifests/<cluster>}); the verb only changes how the
   * synthesis resolves the facet against the branch HEAD (the {@code render-mode} amendment) and
   * the existence guard. It replaces the former single {@code publish} verb, whose implicit
   * follow-HEAD became explicit: {@code update} IS that behaviour.
   */
  private final class DeliverCommand implements CliCommand {

    final DeliverVerb verb;
    final ManifestsCliRun run;

    @SuppressWarnings("unused")
    DeliverCommand(Builder builder) {
      this.verb = builder.verb;
      this.run = builder.run;
    }

    @Override
    public String name() {
      return verb.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public String description() {
      return switch (verb) {
        case INIT ->
            "Render a NEW cluster's manifests from the args + deliver (signed commit + push) — fails"
                + " if manifests/<cluster> already exists (use update/edit)";
        case UPDATE ->
            "Re-render manifests/<cluster> from its RECORDED facet (the grow's posture) + deliver —"
                + " the steady-state render; facet args are ignored";
        case EDIT ->
            "Change facet toggles on manifests/<cluster> over its recorded facet, then re-render +"
                + " deliver — e.g. debug.mesh=true";
        case GROW -> "(internal) apply the seeded facet, as the grow does";
      };
    }

    @Override
    public String usage() {
      return switch (verb) {
        case INIT -> "init [cluster=<host>-mgmt] [node=master] [debug.<x>=bool]";
        case UPDATE -> "update [cluster=<host>-mgmt] [node=master]";
        case EDIT -> "edit [cluster=<host>-mgmt] [node=master] <debug.<x>=bool>…";
        case GROW -> "grow";
      };
    }

    @Override
    public void run() {
      // Drive PublishCliScenario on the embedded launcher — the render + delivery. It sows ghapp →
      // manifests through the broker: ghapp rehydrates the App credentials from .secrets, then
      // manifests renders into the SOIL (resolving the facet per this verb's render-mode), mints a
      // fresh WRITER token on demand from those credentials, and pushes manifests/<cluster>. The
      // same in-container operation the grow drives, minus the Pulumi envelope.
      final String txId = UUID.randomUUID().toString();
      try {
        final ReportModel runbook =
            new JUnitLauncherCore<ReportModel>()
                .run(
                    Main.class.getClassLoader(),
                    JupiterTestEngine.class,
                    wiring -> List.of(DiscoverySelectors.selectClass(PublishCliScenario.class)),
                    (launcher, request, sessionStore) -> {
                      final SummaryGeneratingListener listener = new SummaryGeneratingListener();
                      launcher.execute(request, listener);
                      final var summary = listener.getSummary();
                      if (summary.getTotalFailureCount() > 0) {
                        final var first = summary.getFailures().get(0);
                        throw new IllegalStateException(
                            "the manifests-cli "
                                + name()
                                + " scenario failed: "
                                + first.getTestIdentifier().getDisplayName(),
                            first.getException());
                      }
                      return new ScenarioOutcomeSeed().read(sessionStore).runbook();
                    },
                    PublishCliScenario.SEED
                        .into(run)
                        .andThen(RunRoleSeed.into(RunRole.ROOT))
                        .andThen(TxIdSeed.into(txId))
                        .andThen(LogFileSeed.into(".local.d/manifests-" + name() + ".log")));
        final List<?> broken =
            runbook.getScenariosWithStatus(ExecutionStatus.FAILED, ExecutionStatus.ABORTED);
        if (!broken.isEmpty()) {
          throw new IllegalStateException(
              "the manifests "
                  + name()
                  + " did not complete ("
                  + broken.size()
                  + " failed/aborted)");
        }
        run.identity()
            .ifPresent(
                id ->
                    logger.info(
                        "Manifests rendered into {} and pushed to manifests/{}",
                        run.materializationRoot().orElse("?"),
                        id.clusterName()));
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(
            "the manifests-cli " + name() + " run was interrupted", interrupted);
      }
    }

    static final class Builder implements CommandBuilder<DeliverCommand> {
      private final Main main;

      private DeliverVerb verb = DeliverVerb.UPDATE;
      private ManifestsCliRun run;

      Builder(Main main) {
        this.main = main;
        this.run =
            ManifestsCliRun.of(
                Optional.empty(),
                Optional.empty(),
                main.manifestsFacet(Map.of(), false),
                main.growMode());
      }

      Builder verb(DeliverVerb verb) {
        this.verb = verb;
        return this;
      }

      Builder run(ManifestsCliRun run) {
        this.run = run;
        return this;
      }

      public DeliverCommand build() {
        return main.commandOf(this);
      }

      public Class<DeliverCommand> commandClass() {
        return DeliverCommand.class;
      }
    }
  }

  private final class VersionsCommand implements CliCommand {

    final String level;
    final boolean apply;
    final Optional<String> component;

    @SuppressWarnings("unused")
    VersionsCommand(Builder builder) {
      this.level = builder.level;
      this.apply = builder.apply;
      this.component = builder.component;
    }

    @Override
    public String name() {
      return "versions";
    }

    @Override
    public String description() {
      return "Report — or with apply=true, apply — the pinned component versions against their"
          + " latest upstream GitHub release, bumping (and committing as the rke2lab bot) in place";
    }

    @Override
    public String usage() {
      return "versions [level=major|minor|micro] [apply=true] [component=<id>]";
    }

    /**
     * Resolve the {@code component=<slug>} filter to a typed {@link Component} — fail-loud on an
     * unknown slug (listing the valid ids) rather than silently bumping everything. Empty = all.
     */
    private Optional<Component> resolveComponent() {
      if (component.isEmpty()) {
        return Optional.empty();
      }
      final String slug = component.orElseThrow();
      return Optional.of(
          Component.fromSlug(slug)
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "unknown component '"
                              + slug
                              + "'; valid: "
                              + java.util.Arrays.stream(Component.values())
                                  .map(Component::slug)
                                  .toList())));
    }

    @Override
    public void run() {
      // The bump is a scion: this host root scenario sows the `manifests-versions` coordinate
      // through the broker and grows VersionBumpScenario in-container (where Worktree /
      // AuthTokenContact / NdhKeystoreReader resolve bundle-side, unreachable to the flat host).
      // The reaped runbook — the per-component report, and on apply the bumps + the bot commit — is
      // grafted into this host runbook and rendered to the console below.
      final VersionsCliRun cliRun =
          VersionsCliRun.of(BumpLevel.fromSlug(level), apply, resolveComponent());
      final String txId = UUID.randomUUID().toString();
      try {
        final ReportModel runbook =
            new JUnitLauncherCore<ReportModel>()
                .run(
                    Main.class.getClassLoader(),
                    JupiterTestEngine.class,
                    wiring -> List.of(DiscoverySelectors.selectClass(VersionsCliScenario.class)),
                    (launcher, request, sessionStore) -> {
                      final SummaryGeneratingListener listener = new SummaryGeneratingListener();
                      launcher.execute(request, listener);
                      final var summary = listener.getSummary();
                      if (summary.getTotalFailureCount() > 0) {
                        final var first = summary.getFailures().get(0);
                        throw new IllegalStateException(
                            "the versions scenario failed: "
                                + first.getTestIdentifier().getDisplayName(),
                            first.getException());
                      }
                      return new ScenarioOutcomeSeed().read(sessionStore).runbook();
                    },
                    VersionsCliScenario.SEED
                        .into(cliRun)
                        .andThen(RunRoleSeed.into(RunRole.ROOT))
                        .andThen(TxIdSeed.into(txId))
                        .andThen(LogFileSeed.into(".local.d/manifests-versions.log")));
        renderRunbook(runbook);
        final List<?> broken =
            runbook.getScenariosWithStatus(ExecutionStatus.FAILED, ExecutionStatus.ABORTED);
        if (!broken.isEmpty()) {
          throw new IllegalStateException(
              "the version bump did not complete (" + broken.size() + " failed/aborted)");
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("the versions run was interrupted", interrupted);
      }
    }

    /**
     * Render the reaped runbook to the console with jGiven's own plain-text writer, on {@code
     * System.out} — NOT the SLF4J logger, which rides the JUL bus pax-logging drains into a file
     * once the scenario boots the framework (that is why the report was invisible). A CLI's
     * user-facing report belongs on stdout; {@code System.out} is not flushed-closed (it is the
     * process stream).
     */
    private void renderRunbook(ReportModel runbook) {
      final PrintWriter out = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
      runbook.accept(new PlainTextScenarioWriter(out, false));
      out.flush();
    }

    static final class Builder implements CommandBuilder<VersionsCommand> {
      private final Main main;

      private String level = "minor";
      private boolean apply = false;
      private Optional<String> component = Optional.empty();

      Builder(Main main) {
        this.main = main;
      }

      Builder level(String level) {
        this.level = level;
        return this;
      }

      Builder apply(boolean apply) {
        this.apply = apply;
        return this;
      }

      Builder component(Optional<String> component) {
        this.component = component;
        return this;
      }

      public VersionsCommand build() {
        return main.commandOf(this);
      }

      public Class<VersionsCommand> commandClass() {
        return VersionsCommand.class;
      }
    }
  }

  interface CommandBuilder<T extends Runnable> {
    Class<T> commandClass();

    Runnable build();

    default T cast(Runnable command) {
      if (!commandClass().isInstance(command)) {
        throw new IllegalArgumentException(
            "Expected command of type "
                + commandClass().getName()
                + ", got "
                + command.getClass().getName());
      }
      return commandClass().cast(command);
    }
  }

  interface CliCommand extends Runnable {
    String name();

    String description();

    String usage();
  }

  public <T extends Runnable> T commandOf(CommandBuilder<T> builder) {
    try {
      final Constructor<T> constructor = findCommandConstructor(builder);
      constructor.setAccessible(true);
      return builder.cast(
          constructor.getParameterCount() == 2
              ? constructor.newInstance(this, builder)
              : constructor.newInstance(builder));
    } catch (NoSuchMethodException
        | InstantiationException
        | IllegalAccessException
        | IllegalArgumentException
        | InvocationTargetException e) {
      throw new RuntimeException(
          "Failed to build command of type " + builder.commandClass().getName(), e);
    }
  }

  private <T extends Runnable> Constructor<T> findCommandConstructor(CommandBuilder<T> builder)
      throws NoSuchMethodException {
    final Class<T> commandType = builder.commandClass();
    final Class<?> builderType = builder.getClass();
    for (Constructor<?> candidate : commandType.getDeclaredConstructors()) {
      final Class<?>[] parameterTypes = candidate.getParameterTypes();
      if (parameterTypes.length == 2
          && parameterTypes[0].equals(Main.class)
          && parameterTypes[1].isAssignableFrom(builderType)) {
        @SuppressWarnings("unchecked")
        final Constructor<T> constructor = (Constructor<T>) candidate;
        return constructor;
      }
      if (parameterTypes.length == 1 && parameterTypes[0].isAssignableFrom(builderType)) {
        @SuppressWarnings("unchecked")
        final Constructor<T> constructor = (Constructor<T>) candidate;
        return constructor;
      }
    }

    throw new NoSuchMethodException(
        "No suitable constructor found for "
            + commandType.getName()
            + " with builder type "
            + builderType.getName());
  }
}
