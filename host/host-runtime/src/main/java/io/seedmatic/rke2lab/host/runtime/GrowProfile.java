package io.seedmatic.rke2lab.host.runtime;

import java.util.Locale;
import java.util.Optional;

/**
 * What a grow is FOR — a runtime fact of the run, a sibling of {@link ExecutionEnclosure}.
 *
 * <p>{@link #FLEET} is every cluster of the fleet: its CA minted from the fleet's TLS authority,
 * its age identity on fd 3, the GitHub App, the tailnet, the rendered branch pushed. {@link
 * #THROWAWAY} is a cluster for another project's tests, a kube API only: none of those. It holds no
 * fleet identity and decrypts nothing — no CA is minted (rke2 makes its own), no Secret is sealed,
 * no {@code .secrets} or key-store is opened — so what that project's code can read, it cannot
 * carry back to the fleet.
 *
 * <p>The profile is THROWAWAY only when the stack's committed config ({@code
 * rke2lab:cluster:profile}) and the installed command's {@value #MARKER_ENV} BOTH say so; neither
 * alone selects it, the same double lock {@code #grow} checks before the JVM starts. {@link
 * #resolve} is that rule, so a fleet stack can never lose its identities to a stray variable, nor a
 * throwaway one gain them by a missing one.
 */
public enum GrowProfile {
  FLEET,
  THROWAWAY;

  /** The variable the installed throwaway command sets, which {@code #grow} has confirmed. */
  public static final String MARKER_ENV = "RKE2LAB_GROW_PROFILE";

  /** The value both the stack config and the marker carry for a throwaway grow. */
  public static final String THROWAWAY_NAME = "throwaway";

  /**
   * The profile from the stack's {@code rke2lab:cluster:profile} and the run's marker. Refuses any
   * disagreement, naming both sides, and any profile name it does not know.
   */
  public static GrowProfile resolve(Optional<String> configured, Optional<String> marker) {
    final Optional<String> stack = configured.map(String::strip).filter(v -> !v.isEmpty());
    final Optional<String> run = marker.map(String::strip).filter(v -> !v.isEmpty());
    stack
        .filter(v -> !THROWAWAY_NAME.equals(v.toLowerCase(Locale.ROOT)))
        .ifPresent(
            v -> {
              throw new IllegalStateException(
                  "rke2lab:cluster:profile '"
                      + v
                      + "' is not a grow profile: '"
                      + THROWAWAY_NAME
                      + "', or absent for the fleet");
            });
    final boolean stackSays = stack.isPresent();
    final boolean runSays = run.filter(THROWAWAY_NAME::equals).isPresent();
    if (run.isPresent() && !runSays) {
      throw new IllegalStateException(
          MARKER_ENV + " '" + run.orElseThrow() + "' is not '" + THROWAWAY_NAME + "'");
    }
    if (stackSays != runSays) {
      throw new IllegalStateException(
          stackSays
              ? "this stack is a throwaway one (rke2lab:cluster:profile) but "
                  + MARKER_ENV
                  + " is not set: grow it with the installed rke2lab-throwaway command"
              : MARKER_ENV
                  + " is set but this stack's rke2lab:cluster:profile is not '"
                  + THROWAWAY_NAME
                  + "'");
    }
    return stackSays ? THROWAWAY : FLEET;
  }

  /** Whether this grow takes the fleet's identities and reachability. */
  public boolean isFleet() {
    return this == FLEET;
  }
}
