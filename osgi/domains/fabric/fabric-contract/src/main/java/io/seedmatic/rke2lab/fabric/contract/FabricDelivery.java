package io.seedmatic.rke2lab.fabric.contract;

import java.util.List;

/**
 * What the fabric delivery IS: the branch it lands on and the files it writes there. Single-sourced
 * because these names are read from THREE sides — the scion writes them, nix pins the same branch
 * as the {@code fabric-plan} input and reads the same two files, and the other repos pin it too. A
 * name spelled twice is the mismatch class that cost this project the {@code clusterApi} bug.
 *
 * <p>{@link #canonical()} is the one delivery there is. It is a record rather than a constants
 * holder so a test can state a different target without a static to reach around, which is how the
 * worktree the scion writes into is chosen in a test run.
 */
public record FabricDelivery(String branch, String netplanFile, String dataplanFile) {

  public FabricDelivery {
    if (branch.isBlank() || netplanFile.isBlank() || dataplanFile.isBlank()) {
      throw new IllegalArgumentException(
          "a fabric delivery needs a branch and both file names: " + branch);
    }
  }

  /**
   * The delivery as the fleet knows it: branch {@code fabric/plan}, carrying {@code netplan.json}
   * (netplan's projection — the file that used to sit at the repository root as {@code
   * network-blueprint.json}) and {@code dataplan.json} (the dataset layout).
   */
  public static FabricDelivery canonical() {
    return new FabricDelivery("fabric/plan", "netplan.json", "dataplan.json");
  }

  /** Both file names, in the order the delivery writes them. */
  public List<String> files() {
    return List.of(netplanFile, dataplanFile);
  }
}
