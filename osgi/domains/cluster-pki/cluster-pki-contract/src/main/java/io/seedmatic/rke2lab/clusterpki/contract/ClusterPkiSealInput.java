package io.seedmatic.rke2lab.clusterpki.contract;

import io.seedmatic.rke2lab.seed.broker.port.Amendment;
import io.seedmatic.rke2lab.seed.broker.port.SeedContract;
import java.util.List;

/**
 * The wire contract for the cluster-pki seal's runbook trigger — the ONE amendment the seal scion
 * takes. It carries {@link Amendment#WORKLOAD_TARGETS}: {@link #workloadClusters} is the flat list
 * of CAPI {@code Cluster} names ({@code <host>-<role>}) the management cluster will greenfield, the
 * same {@code workloadTargets} the manifests facet carries. Only the host holds it (from {@code
 * BootstrapConfig}); it fills it by role — never by field name.
 *
 * <p>Everything else the seal needs it reads in-container (the ndh key-store + {@code .sops.yaml}),
 * so this is the seal's ONLY host-held input. Empty on a mgmt-only or standalone run — the seal
 * then mints only the mgmt CA. The {@code @SeedContract} slug is {@code "runbook"} ({@code
 * RunbookCoordinate.SLUG}) so the amend reflector's bearer index resolves it for the {@code
 * cluster-pki} soil, exactly as {@code WebhookReconcileInput} does for {@code ghapp-webhook}.
 */
@SeedContract("runbook")
public record ClusterPkiSealInput(
    @Amendment(Amendment.WORKLOAD_TARGETS) List<String> workloadClusters) {

  public ClusterPkiSealInput {
    workloadClusters = workloadClusters == null ? List.of() : List.copyOf(workloadClusters);
  }
}
