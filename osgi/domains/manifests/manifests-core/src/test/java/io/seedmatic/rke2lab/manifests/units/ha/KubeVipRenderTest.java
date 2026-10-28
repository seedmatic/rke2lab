package io.seedmatic.rke2lab.manifests.units.ha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.manifests.Cdk8sApiObjectResolver;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.YamlMapper;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainPolicy;
import io.seedmatic.rke2lab.manifests.contract.ManifestSynthesisRequest;
import io.seedmatic.rke2lab.manifests.contract.profiles.BootstrapIdentity;
import io.seedmatic.rke2lab.manifests.ingress.Component;
import io.seedmatic.rke2lab.manifests.ingress.ComponentVersions;
import io.seedmatic.rke2lab.manifests.node.DefaultNodeEnvContext;
import java.nio.file.Path;
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
 * kube-vip as the 1.x line reads it: the env names it parses (a misnamed one is silently ignored,
 * as {@code vip_cidr} was), and the EndpointSlice access its default watcher needs.
 */
@Tag("osgi")
class KubeVipRenderTest {

  private static final ManifestDomainPolicy POLICY = ManifestDomainPolicy.builder().build();

  private static final BootstrapIdentity IDENTITY =
      new BootstrapIdentity(
          "nikopol-mgmt",
          1,
          "token",
          "cluster.local",
          "dev",
          "master",
          1,
          "workload",
          "nikopol-nixos",
          "nikopol-mgmt-master",
          "nikopol-mgmt-master.nikopol");

  @SuppressWarnings("unchecked")
  private static Map<String, Object> rendered(final Path outdir, final String kind) {
    final App app = new App(AppProps.builder().outdir(outdir.toString()).build());
    final Chart chart = new Chart(app, "manifests");
    final ManifestSynthesisRequest request =
        ManifestSynthesisRequest.builder(outdir, outdir.resolve("manifests.yaml"))
            .bootstrapIdentity(IDENTITY)
            .manifestDomainPolicy(Optional.of(POLICY))
            .build();
    final KubeVipManifestsUnit unit = new KubeVipManifestsUnit();
    try (var bound = ManifestSynthesisContext.of(request).bind()) {
      unit.apply(
          new ManifestsUnitContext(
              chart,
              "high-availability",
              unit.manifestUnitId(),
              new Cdk8sApiObjectResolver(chart),
              POLICY,
              new DefaultNodeEnvContext(IDENTITY),
              new YamlMapper()));
    }
    for (final Object document : chart.toJson()) {
      final Map<String, Object> d = (Map<String, Object>) document;
      if (kind.equals(d.get("kind"))) {
        return d;
      }
    }
    throw new AssertionError("no " + kind + " rendered");
  }

  @Test
  @SuppressWarnings("unchecked")
  void the_daemon_set_runs_the_pinned_image_with_the_env_names_kube_vip_parses(
      @TempDir Path outdir) {
    final Map<String, Object> spec =
        (Map<String, Object>)
            ((Map<String, Object>)
                    ((Map<String, Object>) rendered(outdir, "DaemonSet").get("spec"))
                        .get("template"))
                .get("spec");
    final Map<String, Object> container =
        ((List<Map<String, Object>>) spec.get("containers")).get(0);
    assertEquals(
        "ghcr.io/kube-vip/kube-vip:" + ComponentVersions.defaults().of(Component.KUBE_VIP),
        container.get("image"));
    final List<String> names =
        ((List<Map<String, String>>) container.get("env"))
            .stream().map(e -> e.get("name")).toList();
    assertTrue(names.contains("vip_subnet"), names.toString());
    assertTrue(!names.contains("vip_cidr"), names.toString());
  }

  @Test
  @SuppressWarnings("unchecked")
  void the_role_lets_the_default_watcher_read_endpoint_slices(@TempDir Path outdir) {
    final List<Map<String, Object>> rules =
        (List<Map<String, Object>>) rendered(outdir, "ClusterRole").get("rules");
    assertTrue(
        rules.stream()
            .anyMatch(
                r ->
                    ((List<String>) r.get("apiGroups")).contains("discovery.k8s.io")
                        && ((List<String>) r.get("resources")).contains("endpointslices")
                        && ((List<String>) r.get("verbs")).contains("watch")),
        rules.toString());
  }
}
