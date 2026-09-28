// @codebase
package io.seedmatic.rke2lab.manifests.profiles;

import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.contract.FloxAnnotation;
import io.seedmatic.rke2lab.manifests.contract.profiles.FloxDebugPolicy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Adds a {@code shell} sidecar to a flox-injected workload pod for live inspection.
 *
 * <p>The sidecar shares the prod container's volume mounts plus a per-pod flox state set ({@code
 * HOME}, {@code ~/.config/flox}, {@code ~/.cache/flox}) so an operator can {@code kubectl exec -it
 * ... -c shell -- bash} and run {@code flox activate -- ...} against the same flox view the prod
 * container is using. With {@link #shareProcessNamespace()} = {@code true} on the pod and {@code
 * SYS_PTRACE} added to the sidecar's capabilities, the operator can also {@code dlv attach $(pgrep
 * <workload>)} and step through the live workload — provided the prod build retains debug symbols
 * (the layer-side flox env should drop {@code -s -w} from the workload's go ldflags).
 *
 * <p>The sidecar is opted in via the per-domain debug toggle the layer passes in (e.g. {@code
 * floxDebugPolicy.meshEnabled()} for {@code mesh/*} workloads, {@code networkingEnabled()} for
 * {@code networking/*}). When disabled, every accessor returns an empty/no-op shape so the
 * layer-side wiring stays uniform.
 *
 * <p>The flox env injected into the sidecar is its own per-container annotation ({@code
 * flox.seedmatic.io/environment.shell}); it can match the prod env or point at a debug variant like
 * {@code networking/kdns-debug} that adds {@code delve} on top.
 */
public final class FloxShellSidecarProfile {

  private static final String HOME_VOLUME_SUFFIX = "-shell-home";
  private static final String FLOX_CONFIG_VOLUME_SUFFIX = "-shell-flox-config";
  private static final String FLOX_CACHE_VOLUME_SUFFIX = "-shell-flox-cache";

  private final FloxDebugPolicy policy;
  private final boolean enabled;
  private final String workloadName;
  private final String homePath;
  private final String floxEnvironment;
  private final String uid;
  private final String gid;

  public FloxShellSidecarProfile(
      final FloxDebugPolicy policy,
      final boolean enabled,
      final String workloadName,
      final String homePath,
      final String floxEnvironment,
      final String uid,
      final String gid) {
    this.policy = policy;
    this.enabled = enabled;
    this.workloadName = workloadName;
    this.homePath = homePath;
    this.floxEnvironment = floxEnvironment;
    this.uid = uid;
    this.gid = gid;
  }

  public boolean enabled() {
    return enabled;
  }

  /**
   * Whether the pod spec needs {@code shareProcessNamespace: true}. Required for {@code dlv attach}
   * to see the prod PID from the sidecar. {@code true} only when debug is enabled.
   */
  public boolean shareProcessNamespace() {
    return enabled();
  }

  /**
   * Sidecar container spec; empty when the debug policy is disabled. The caller passes the prod
   * container's *final* volumeMounts (i.e. after augmenting with {@link #extraProdMounts()}) so the
   * sidecar inherits the same mount table verbatim — same flox state, same workload mounts.
   *
   * <p>The sidecar adds {@code SYS_PTRACE} so {@code dlv attach $(pgrep <workload>)} works against
   * the prod container's process (visible via the shared PID namespace).
   */
  public Optional<Map<String, Object>> sidecar(final List<Map<String, Object>> prodMounts) {
    if (!enabled()) {
      return Optional.empty();
    }
    LinkedHashMap<String, Object> container = new LinkedHashMap<>();
    container.put("name", sidecarContainerName());
    container.put("image", ManifestSynthesisContext.current().containerImages().carrier());
    container.put("imagePullPolicy", "IfNotPresent");
    container.put("command", List.of("/bin/sleep", "infinity"));
    container.put(
        "env",
        List.of(
            Map.of("name", "HOME", "value", homePath), Map.of("name", "USER", "value", "root")));
    container.put(
        "resources",
        Map.of(
            "limits",
            Map.of("cpu", "100m", "ephemeral-storage", "128Mi", "memory", "128Mi"),
            "requests",
            Map.of("cpu", "10m", "ephemeral-storage", "64Mi", "memory", "64Mi")));
    container.put(
        "securityContext",
        Map.of(
            "allowPrivilegeEscalation",
            false,
            "capabilities",
            Map.of("drop", List.of("ALL"), "add", List.of("SYS_PTRACE")),
            "readOnlyRootFilesystem",
            false,
            "runAsNonRoot",
            false,
            "runAsUser",
            0));
    container.put("volumeMounts", List.copyOf(prodMounts));
    return Optional.of(Map.copyOf(container));
  }

  /**
   * Pod-level volumes the layer must add when the sidecar is enabled: a shared flox HOME + per-pod
   * flox config/cache emptyDirs. Empty list when disabled.
   */
  public List<Map<String, Object>> extraVolumes() {
    if (!enabled()) {
      return List.of();
    }
    return List.of(
        Map.of("name", homeVolumeName(), "emptyDir", Map.of()),
        Map.of("name", floxConfigVolumeName(), "emptyDir", Map.of()),
        Map.of("name", floxCacheVolumeName(), "emptyDir", Map.of()));
  }

  /**
   * Mounts the prod container should add so its flox state lives in the same per-pod emptyDirs the
   * sidecar sees. Empty list when disabled — live manifests stay byte-identical to today.
   */
  public List<Map<String, Object>> extraProdMounts() {
    if (!enabled()) {
      return List.of();
    }
    return List.of(
        Map.of("name", homeVolumeName(), "mountPath", homePath),
        Map.of("name", floxConfigVolumeName(), "mountPath", "/.config/flox"),
        Map.of("name", floxCacheVolumeName(), "mountPath", "/.cache/flox"));
  }

  /**
   * Pod-template annotations the layer should merge into the workload's annotation map. Carries the
   * sidecar's flox env + identity so the NRI plugin injects the right env per container.
   */
  public Map<String, String> sidecarAnnotations() {
    if (!enabled()) {
      return Map.of();
    }
    final String c = sidecarContainerName();
    LinkedHashMap<String, String> annotations = new LinkedHashMap<>();
    annotations.put(FloxAnnotation.ENVIRONMENT.forContainer(c), floxEnvironment);
    annotations.put(FloxAnnotation.HOME.forContainer(c), homePath);
    annotations.put(FloxAnnotation.UID.forContainer(c), uid);
    annotations.put(FloxAnnotation.GID.forContainer(c), gid);
    return Map.copyOf(annotations);
  }

  private String sidecarContainerName() {
    // One shell sidecar per pod, so a bare "shell" is unambiguous and reads far better
    // in `kubectl`/logs than "<workload>-shell". Its debug-only nature is implied — it
    // exists only when the debug toggle is on.
    return "shell";
  }

  private String homeVolumeName() {
    return workloadName + HOME_VOLUME_SUFFIX;
  }

  private String floxConfigVolumeName() {
    return workloadName + FLOX_CONFIG_VOLUME_SUFFIX;
  }

  private String floxCacheVolumeName() {
    return workloadName + FLOX_CACHE_VOLUME_SUFFIX;
  }
}
