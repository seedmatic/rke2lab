// @codebase
package io.seedmatic.rke2lab.manifests.contract.profiles;

import java.util.List;
import java.util.Objects;

/**
 * The container contract an instance of the node-base image must carry to run a Kubernetes node —
 * the {@code linux.kernel_modules}, {@code raw.lxc} and {@code security.*} incus keys, carried as a
 * sub-record of {@link ImageState} and rendered into the {@code NodeImage} CR's {@code
 * spec.runtime}.
 *
 * <p>A blind subtree mirroring the incus scion's amendment JSON, naming no incus type — the VALUES
 * have exactly one definition, {@code NodeRuntimeContract.nodeBase()} in the incus domain, and only
 * the SHAPE is mirrored across the membrane (the same discipline as {@code
 * ManifestsRunbookInput.Identity}).
 *
 * <p>★ Why it travels at all: the in-cluster controller poses this contract on {@code
 * LXCMachine.spec.config}, where INSTANCE config wins over the profile the host GROW stamps. The
 * deciding copy is therefore the in-cluster one, and while it was a literal in the controller it
 * drifted from the host's — losing {@code xfrm_user} and {@code nft_compat} with the four {@code
 * xt_*} extensions, each of which had been established by loading it and watching the cilium agent.
 * Publishing the contract makes the two structurally identical instead of merely reconciled.
 */
public record NodeRuntime(
    List<String> kernelModules,
    String rawLxc,
    boolean privileged,
    boolean nesting,
    boolean interceptBpf,
    boolean interceptBpfDevices) {

  public NodeRuntime {
    kernelModules = List.copyOf(Objects.requireNonNull(kernelModules, "kernelModules"));
    if (kernelModules.isEmpty()) {
      throw new IllegalArgumentException(
          "kernelModules is empty — an instance with no module set cannot run cilium");
    }
    rawLxc = Objects.requireNonNull(rawLxc, "rawLxc");
  }
}
