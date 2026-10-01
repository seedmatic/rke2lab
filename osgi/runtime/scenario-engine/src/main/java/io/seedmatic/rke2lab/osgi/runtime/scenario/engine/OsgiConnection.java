package io.seedmatic.rke2lab.osgi.runtime.scenario.engine;

import io.seedmatic.rke2lab.osgi.runtime.framework.BootedFramework;
import io.seedmatic.rke2lab.osgi.runtime.framework.FrameworkLaunch;
import java.util.Optional;
import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.launch.Framework;
import org.osgi.service.log.LogLevel;
import org.osgi.util.tracker.ServiceTracker;

/**
 * A connection to the OSGi world — the first step of a seed scenario ("the OSGi world is
 * connected") and the socle's lifecycle handle (spec Figure 3). It is a CONTRACT over a live {@link
 * BundleContext}, NOT a boot mechanism: it exposes only what the socle consumes — {@link
 * #context()} to reach the world (the {@link StartLevelLever} adapts {@code getBundle(0)} to {@code
 * FrameworkStartLevel}), {@link #ownsLifecycle()} to know whether teardown should stop the world,
 * and {@link #close()} to release it. Asking for a capability (a context + a close), not a producer
 * identity, is what lets one contract serve every boot: embedded, attached, and — later — remote.
 *
 * <p>Two faces (Figure 3):
 *
 * <ul>
 *   <li>{@link #embedded()} — REALISED: bootstraps Felix in-process (the prod boot from the staged
 *       bundles a deployed exec-jar carries), owns the lifecycle, and its {@code close()} stops the
 *       world. This is the seed's first step in increment 2.
 *   <li>{@link #remote(String)} — NAMED, not built: would attach to a running world and own
 *       nothing, so its {@code close()} would merely detach. Throws until a real client-framework
 *       case arrives (the spec's YAGNI). The fine contract is exactly what makes it cheap to add:
 *       it will return a connection with {@code ownsLifecycle() == false} and a detach action.
 * </ul>
 *
 * <p>{@link #over(BundleContext, boolean, Runnable)} wraps an already-booted world under the same
 * contract — how a test opens a connection over a Felix booted by the testkit, and how {@code
 * embedded()} itself is expressed over the booted framework.
 */
public interface OsgiConnection extends AutoCloseable {

  /**
   * The live world's bundle context — the one capability the socle reaches the framework through.
   */
  BundleContext context();

  /**
   * Whether {@link #close()} must stop the world (embedded) or merely detach from it (attached).
   */
  boolean ownsLifecycle();

  /**
   * An optional LDAP filter fragment the connection ANDs into every {@link #awaitService} lookup —
   * the connection's "which variant of a service" selector. Empty in prod ({@link #embedded()}
   * takes the plain live service). A TEST connection carries e.g. {@code (variant=fake)} so the
   * host-agnostic stages resolve the fake {@code @Component} instead of the live one, WITHOUT the
   * stage (or the staging) knowing: the driver that produces the connection is the sole thing that
   * knows the world and the variant, and it says so here — once, on the connection, portable across
   * embedded/attached/remote.
   */
  default Optional<String> serviceSelector() {
    return Optional.empty();
  }

  /** Release the connection: stop the world if owned, else detach — never throws checked. */
  @Override
  void close();

  /**
   * The {@link Framework} this connection is attached to — the system bundle IS the Framework
   * ({@code getBundle(0)}). A cast, not an {@code adapt}: the system bundle's runtime identity, not
   * a capability face. For the few collaborators that take a whole {@code BootedFramework} (e.g.
   * the entry-gate enforcer); to read ONE service, prefer {@link #awaitService} — it needs no
   * wrapper.
   */
  default Framework framework() {
    return (Framework) context().getBundle(0);
  }

  /**
   * Resolve a single service of {@code type} from the world's registry, waiting up to {@code
   * timeoutMillis} for SCR to publish it (a component's service appears only after its mandatory
   * references bind). The connection owns the context, so it does the lookup itself — a phase reads
   * {@code connection.awaitService(X.class, …)} rather than wrapping the framework in a
   * service-lookup view. The service registry crosses all bundles, so this is a broker over the
   * whole world, not a classloader-bounded {@code adapt}.
   */
  default <T> T awaitService(Class<T> type, long timeoutMillis) {
    final ServiceTracker<T, T> tracker = trackerFor(type);
    tracker.open();
    try {
      return tracker.waitForService(timeoutMillis);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted awaiting service " + type.getName(), ex);
    } finally {
      tracker.close();
    }
  }

  /**
   * A tracker for {@code type}, narrowed by {@link #serviceSelector()} when present: the
   * objectClass clause ANDed with the connection's variant filter. No selector → track by class
   * (the plain live path). The filter string is jointly owned here so the two branches cannot
   * drift.
   */
  private <T> ServiceTracker<T, T> trackerFor(Class<T> type) {
    if (serviceSelector().isEmpty()) {
      return new ServiceTracker<>(context(), type, null);
    }
    final String filter = "(&(objectClass=" + type.getName() + ")" + serviceSelector().get() + ")";
    try {
      return new ServiceTracker<>(context(), context().createFilter(filter), null);
    } catch (InvalidSyntaxException ex) {
      throw new IllegalStateException("malformed service selector filter: " + filter, ex);
    }
  }

  /**
   * Bootstrap Felix in-process and own its lifecycle — the prod boot (spec Figure 3, REALISED).
   * Boots from the bundles staged under {@code META-INF/bundles/} that a deployed exec-jar carries
   * (via {@link FrameworkLaunch#embedded()}), so it runs in the seed's own artifact, not in a bare
   * library module. {@code close()} stops the world.
   */
  static OsgiConnection embedded() {
    final BootedFramework booted = FrameworkLaunch.embedded().launch();
    return over(booted.context(), true, booted::close);
  }

  /**
   * As {@link #embedded()}, but with the boot's two operator knobs threaded from the launcher: the
   * framework's own log verbosity ({@code level} — the {@code logging:level} knob, so a failed
   * resolve explains WHICH requirement could not be wired) and the boot log's file ({@code logFile}
   * — so each exec keeps its own trace instead of a shared {@code seed-outcluster.log}). Either
   * empty ⇒ the Felix default for that knob. The one entry point {@code BaseWorldExtension} boots
   * through.
   */
  static OsgiConnection embedded(Optional<LogLevel> level, Optional<String> logFile) {
    final FrameworkLaunch.Embedded preset =
        level.isPresent()
            ? logFile
                .map(file -> FrameworkLaunch.embedded(level.get(), file))
                .orElseGet(() -> FrameworkLaunch.embedded(level.get()))
            : logFile.map(FrameworkLaunch::embedded).orElseGet(FrameworkLaunch::embedded);
    final BootedFramework booted = preset.launch();
    return over(booted.context(), true, booted::close);
  }

  /**
   * Wrap an ALREADY-booted world under the connection contract: {@code context} is the live world,
   * {@code ownsLifecycle} says whether {@code close()} stops it, {@code onClose} is the teardown to
   * run on {@link #close()} (stop the world when owned, detach when attached).
   */
  static OsgiConnection over(BundleContext context, boolean ownsLifecycle, Runnable onClose) {
    return over(context, ownsLifecycle, onClose, Optional.empty());
  }

  /**
   * As {@link #over(BundleContext, boolean, Runnable)}, plus the {@link #serviceSelector()} the
   * connection ANDs into every service lookup — the seam a TEST driver uses to make the
   * host-agnostic stages resolve fake {@code @Component}s (e.g. {@code
   * Optional.of("(variant=fake)")}).
   */
  static OsgiConnection over(
      BundleContext context,
      boolean ownsLifecycle,
      Runnable onClose,
      Optional<String> serviceSelector) {
    return new OsgiConnection() {
      @Override
      public BundleContext context() {
        return context;
      }

      @Override
      public boolean ownsLifecycle() {
        return ownsLifecycle;
      }

      @Override
      public Optional<String> serviceSelector() {
        return serviceSelector;
      }

      @Override
      public void close() {
        onClose.run();
      }
    };
  }

  /**
   * Attach to a running world reachable at {@code endpoint} — NAMED, not built (spec Figure 3).
   * Would own nothing; its {@code close()} would detach, leaving the world alive. Throws until a
   * real client-framework case arrives (§yagni).
   */
  static OsgiConnection remote(String endpoint) {
    throw new UnsupportedOperationException("remote OSGi connection not yet realised: " + endpoint);
  }
}
