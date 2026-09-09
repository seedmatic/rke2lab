package io.seedmatic.rke2lab.manifests.contract;

import io.seedmatic.rke2lab.seed.broker.port.Amendment;
import io.seedmatic.rke2lab.seed.broker.port.SeedContract;
import java.util.Objects;

/**
 * The wire contract for the {@code incus-identity} seal's runbook trigger — the host-world part of
 * the CAPN provider identity, carried as the ONE {@link Amendment#INCUS_IDENTITY} the seal scion
 * takes. Only the host can read these three: the incus remote {@code serverAddress}
 * (BootstrapConfig), the {@code serverCert} (operator {@code ~/.config/incus/servercerts/*.crt}),
 * and the capn-provider {@code clientCert} (a bundled seed-master classpath resource). The client
 * KEY is NOT here — the seal scion reads it in-container from {@code .secrets:incus.capn.clientKey}
 * via the {@code SecretsGateway}, so the private key never rides this wire nor the git branch.
 *
 * <p>{@link #host} is REQUIRED — the incus identity is mandatory (a workload cannot be grown
 * without it), so it is NOT an {@code Optional} and NOT nullable: an absent amendment decodes to a
 * null host and the compact constructor fails loud rather than tolerating a fallback. The
 * {@code @SeedContract} slug is {@code "runbook"} ({@code RunbookCoordinate.SLUG}) so the amend
 * reflector's bearer index resolves it for the {@code incus-identity} soil, exactly as {@code
 * ClusterPkiSealInput} does for {@code cluster-pki}.
 */
@SeedContract("runbook")
public record IncusIdentitySealInput(@Amendment(Amendment.INCUS_IDENTITY) HostIncusIdentity host) {

  public IncusIdentitySealInput {
    host =
        Objects.requireNonNull(
            host,
            "cannot grow: no incus identity supplied (the INCUS_IDENTITY amendment was absent) —"
                + " CAPN cannot authenticate to incus without it");
  }

  /**
   * The three host-world creds — always complete: the compact constructor rejects a null or blank
   * field, so a {@code HostIncusIdentity} cannot exist half-formed (a missing cred fails loud at
   * decode rather than producing an unusable identity).
   */
  public record HostIncusIdentity(String serverAddress, String serverCert, String clientCert) {

    public HostIncusIdentity {
      requirePresent(serverAddress, "serverAddress");
      requirePresent(serverCert, "serverCert");
      requirePresent(clientCert, "clientCert");
    }

    private static void requirePresent(String value, String field) {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException(
            "cannot grow: the incus identity "
                + field
                + " is missing — check the incus remote in"
                + " ~/.config/incus and the bundled capn client cert");
      }
    }
  }
}
