package io.seedmatic.rke2lab.manifests.units.runtime.flox;

/**
 * The GC-root category a flox env lives under on the node ({@code <base>/<folder>/<name>}) — the
 * single source for a {@code FloxEnv} CR's {@code spec.folder} and, conceptually, the {@code
 * flox.seedmatic.io/environment.<container>} pod annotation's {@code <folder>/} prefix that the
 * flox NRI plugin keys on. A typed vocabulary prevents the folder ↔ annotation-prefix mismatches
 * that would silently misroute a workload's env (the plugin would readlink a GC-root the controller
 * never provisioned).
 *
 * <p>Lives in {@code manifests-core} for now, consumed only by {@link FloxEnvManifestsUnit}. It
 * promotes to {@code manifests-contract} (with a spec) once the {@code "mesh/…"} / {@code
 * "networking/…"} compound strings in {@code FloxDebugPolicy.resolve*Environment} call sites are
 * unified to derive from it.
 */
public enum FloxEnvFolder {
  NETWORKING("networking"),
  MESH("mesh"),
  // The cluster-api tier — in-cluster CAPI machinery delivered on the flox runtime instead of a
  // baked node-base image. Holds the rke2-adoption-controller env (the Go controller binary).
  CLUSTER_API("cluster-api"),
  // The cross-cutting toolchain tier — NOT a workload domain. `toolchains` holds composable
  // capability envs a step activates or `[include]`s regardless of domain: `git-sops` (git + sops +
  // the ndh filter, so a checkout smudges the sops tree) and `kube` (the kube-API scripting tools
  // kubectl + yq-go that helper Jobs activate / domain envs `[include]`). Kept separate from the
  // domain folders so a folder never implies a consumer.
  TOOLCHAINS("toolchains");

  private final String value;

  FloxEnvFolder(final String value) {
    this.value = value;
  }

  /** The on-node path segment (and annotation prefix), e.g. {@code "mesh"}. */
  public String value() {
    return value;
  }
}
