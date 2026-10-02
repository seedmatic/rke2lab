package io.seedmatic.rke2lab.controlplane;

import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

/**
 * A JVM agent — wired through the exec jar's {@code Launcher-Agent-Class} manifest entry, so it
 * runs before {@link Main#main} with no {@code -javaagent} flag and no self-attach — that throttles
 * a busy-poll in the Pulumi Java SDK's task runner.
 *
 * <p>pulumi-java's {@code DeploymentImpl$DefaultRunner.loopUntilDone} reschedules itself with NO
 * backoff while any task is in flight, and its {@code checkForTasks} eagerly {@code String.format}s
 * the whole in-flight task map into a {@code FINEST} log call that is never emitted. Whenever a
 * task lingers — the multi-minute cold nix node-base build is the case that hurts — this pegs most
 * cores for the task's entire duration. The code is byte-identical through pulumi-java v1.35.0, so
 * a version bump does not fix it; bounding the ForkJoinPool starves unrelated async work (JGit's
 * filesystem-timestamp probe runs on the common pool) into a hang; and moving the build to a Pulumi
 * resource only shifts the long-lived in-flight task, it does not remove it. The one safe seam is
 * to patch the loop itself: a load-time {@link Advice} that sleeps briefly on entry to {@code
 * loopUntilDone}. Each reschedule re-enters the method, so the sleep caps the poll at ~40 Hz — the
 * CPU spin and the eager-format cost collapse, while the drain still completes within one sleep of
 * the last task (nothing against a build measured in minutes). A worker that sleeps leaves the rest
 * of the pool free, so unlike the FJP clamp it cannot starve JGit.
 *
 * <p>The runner is matched by class + method name, so the patch survives pulumi bumps; if a future
 * release renames the method the transform simply never fires — the install listener writes to
 * {@code System.err}, so a silent regression stays visible.
 */
public final class PulumiRunnerThrottleAgent {

  private static final String RUNNER =
      "com.pulumi.deployment.internal.DeploymentImpl$DefaultRunner";

  private PulumiRunnerThrottleAgent() {}

  /** Entry point for the {@code Launcher-Agent-Class} manifest attribute (executable-jar agent). */
  public static void agentmain(String args, Instrumentation instrumentation) {
    install(instrumentation);
  }

  /** Symmetry: honoured too if this is ever attached as a {@code -javaagent} premain. */
  public static void premain(String args, Instrumentation instrumentation) {
    install(instrumentation);
  }

  private static void install(Instrumentation instrumentation) {
    new AgentBuilder.Default()
        .with(AgentBuilder.Listener.StreamWriting.toSystemError().withTransformationsOnly())
        .type(ElementMatchers.named(RUNNER))
        .transform(
            (builder, typeDescription, classLoader, module, protectionDomain) ->
                builder.visit(
                    Advice.to(ThrottleAdvice.class).on(ElementMatchers.named("loopUntilDone"))))
        .installOn(instrumentation);
  }

  /** Inlined into {@code loopUntilDone}: a short, defensive sleep on every poll iteration. */
  public static final class ThrottleAdvice {

    private ThrottleAdvice() {}

    @Advice.OnMethodEnter
    static void enter() {
      try {
        Thread.sleep(25L);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
