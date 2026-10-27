package io.seedmatic.rke2lab.manifests.units;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.manifests.Cdk8sApiObjectResolver;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsDomainRegistrar;
import io.seedmatic.rke2lab.manifests.ManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.YamlMapper;
import io.seedmatic.rke2lab.manifests.contract.ClusterFleet;
import io.seedmatic.rke2lab.manifests.contract.ControlPlaneShape;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainPolicy;
import io.seedmatic.rke2lab.manifests.contract.ManifestSynthesisRequest;
import io.seedmatic.rke2lab.manifests.contract.profiles.BootstrapIdentity;
import io.seedmatic.rke2lab.manifests.domain.CicdDomainRegistrar;
import io.seedmatic.rke2lab.manifests.domain.ClusterApiDomainRegistrar;
import io.seedmatic.rke2lab.manifests.domain.ClusterDomainRegistrar;
import io.seedmatic.rke2lab.manifests.domain.GitopsDomainRegistrar;
import io.seedmatic.rke2lab.manifests.domain.HighAvailabilityDomainRegistrar;
import io.seedmatic.rke2lab.manifests.domain.NetworkingDomainRegistrar;
import io.seedmatic.rke2lab.manifests.domain.PlatformDomainRegistrar;
import io.seedmatic.rke2lab.manifests.domain.RuntimeDomainRegistrar;
import io.seedmatic.rke2lab.manifests.domain.StorageDomainRegistrar;
import io.seedmatic.rke2lab.manifests.domain.TailscaleDomainRegistrar;
import io.seedmatic.rke2lab.manifests.node.DefaultNodeEnvContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.cdk8s.App;
import org.cdk8s.AppProps;
import org.cdk8s.Chart;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A tenant's nodes run its workload and nothing of the fleet's: they are tainted {@code
 * project=<tenant>:NoSchedule}, and no fleet pod template may tolerate that taint. A pod that does
 * lands there, and with it what it mounts (the flox DaemonSet the App's github-token, kdns the
 * host's network): an escape from the tenant's pod would reach them.
 *
 * <p>It rests on the TAINT, not on a label: NodeRestriction forbids a kubelet to change its own
 * taints, but not its unprefixed labels, so an affinity on {@code project} is one a compromised
 * tenant node could undo.
 *
 * <p>Every unit of every registrar is rendered (the list is explicit, as in RealDomainGraphTest).
 * HelmCharts are out of its sight: their pods are rendered in the cluster, from the chart's own
 * defaults.
 */
@Tag("osgi")
class TenantNodesTest {

  private static final Map<String, String> TENANT_TAINT =
      Map.of("key", "project", "value", "ccr", "effect", "NoSchedule");

  private static final List<String> POD_KINDS =
      List.of("Deployment", "DaemonSet", "StatefulSet", "Job", "ReplicaSet", "Pod");

  @Test
  void requireNoFleetPodToleratesATenantsTaint(@TempDir Path outdir) {
    final List<String> tolerating = new ArrayList<>();
    int pods = 0;
    for (final Object doc : renderEveryUnit(outdir)) {
      final Map<?, ?> document = (Map<?, ?>) doc;
      if (!POD_KINDS.contains(document.get("kind"))) {
        continue;
      }
      pods++;
      final Map<?, ?> spec = (Map<?, ?>) document.get("spec");
      final Map<?, ?> podSpec =
          "Pod".equals(document.get("kind"))
              ? spec
              : (Map<?, ?>) ((Map<?, ?>) spec.get("template")).get("spec");
      final Object tolerations = podSpec.get("tolerations");
      if (tolerations != null && tolerates(tolerations)) {
        tolerating.add(
            document.get("kind") + "/" + ((Map<?, ?>) document.get("metadata")).get("name"));
      }
    }
    assertTrue(pods >= 14, "the render lost pod templates: " + pods);
    assertEquals(List.of(), tolerating, "fleet pods that would land on a tenant's node");
  }

  /** Kubernetes' ToleratesTaint, for one taint. */
  private static boolean tolerates(final Object tolerations) {
    final Iterable<?> list =
        tolerations instanceof Object[] array ? List.of(array) : (Iterable<?>) tolerations;
    for (final Object raw : list) {
      final Map<?, ?> toleration = (Map<?, ?>) raw;
      final Object effect = toleration.get("effect");
      if (effect != null && !"".equals(effect) && !TENANT_TAINT.get("effect").equals(effect)) {
        continue;
      }
      final Object key = toleration.get("key");
      final boolean anyKey = key == null || "".equals(key);
      if (!anyKey && !TENANT_TAINT.get("key").equals(key)) {
        continue;
      }
      final Object operator = toleration.get("operator");
      if ("Exists".equals(operator)) {
        return true;
      }
      if (!anyKey && TENANT_TAINT.get("value").equals(toleration.get("value"))) {
        return true;
      }
    }
    return false;
  }

  private static List<Object> renderEveryUnit(final Path outdir) {
    final List<ManifestsDomainRegistrar> registrars =
        List.of(
            new CicdDomainRegistrar(),
            new ClusterApiDomainRegistrar(),
            new ClusterDomainRegistrar(),
            new GitopsDomainRegistrar(),
            new HighAvailabilityDomainRegistrar(),
            new NetworkingDomainRegistrar(),
            new PlatformDomainRegistrar(),
            new RuntimeDomainRegistrar(),
            new StorageDomainRegistrar(),
            new TailscaleDomainRegistrar());
    final ManifestDomainPolicy policy =
        ManifestDomainPolicy.builder()
            .enableOnly(ManifestDomainCatalog.builder().addDefaultDomains().build().all())
            .build();
    final BootstrapIdentity identity =
        new BootstrapIdentity(
            "nikopol-wrkld",
            5,
            "token",
            "cluster.local",
            "dev",
            "master",
            1,
            "workload",
            "nikopol-nixos",
            "nikopol-wrkld-master",
            "nikopol-wrkld-master.nikopol");
    final App app = new App(AppProps.builder().outdir(outdir.toString()).build());
    final Chart chart = new Chart(app, "manifests");
    final ManifestSynthesisRequest request =
        ManifestSynthesisRequest.builder(outdir, outdir.resolve("manifests.yaml"))
            .bootstrapIdentity(identity)
            .clusterFleet(
                Optional.of(
                    new ClusterFleet(
                        List.of("bioskop", "nikopol"), "bioskop", ControlPlaneShape.HA)))
            .manifestDomainPolicy(Optional.of(policy))
            .fabricBridgeParent(Optional.of("fabric-br"))
            .build();
    try (var bound = ManifestSynthesisContext.of(request).bind()) {
      for (final ManifestsDomainRegistrar registrar : registrars) {
        for (final ManifestsUnit unit : registrar.domain().units()) {
          unit.apply(
              new ManifestsUnitContext(
                  chart,
                  registrar.domain().domainId(),
                  unit.manifestUnitId(),
                  new Cdk8sApiObjectResolver(chart),
                  policy,
                  new DefaultNodeEnvContext(identity),
                  new YamlMapper()));
        }
      }
    }
    return chart.toJson();
  }
}
