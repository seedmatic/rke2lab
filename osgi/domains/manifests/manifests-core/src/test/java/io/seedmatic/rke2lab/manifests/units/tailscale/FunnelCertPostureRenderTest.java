package io.seedmatic.rke2lab.manifests.units.tailscale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.Cdk8sApiObjectResolver;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.YamlMapper;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainPolicy;
import io.seedmatic.rke2lab.manifests.contract.ManifestSynthesisRequest;
import io.seedmatic.rke2lab.manifests.contract.profiles.BootstrapIdentity;
import io.seedmatic.rke2lab.manifests.ingress.FunnelCertIssuance;
import io.seedmatic.rke2lab.manifests.ingress.FunnelLeaf;
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
 * The Funnel's cert posture reaches the operator where it reads it: each Funnel ProxyClass's {@code
 * spec.useLetsEncryptStagingEnvironment}, from the same {@link FunnelCertIssuance} the ghapp
 * webhook's {@code insecure_ssl} reads. The chart reads no such value, so none is passed to it.
 */
@Tag("osgi")
class FunnelCertPostureRenderTest {

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
  private static List<Map<String, Object>> rendered(
      final Path outdir, final AbstractManifestsUnit unit, final String kind) {
    final App app = new App(AppProps.builder().outdir(outdir.toString()).build());
    final Chart chart = new Chart(app, "manifests");
    final ManifestSynthesisRequest request =
        ManifestSynthesisRequest.builder(outdir, outdir.resolve("manifests.yaml"))
            .bootstrapIdentity(IDENTITY)
            .manifestDomainPolicy(Optional.of(POLICY))
            .build();
    try (var bound = ManifestSynthesisContext.of(request).bind()) {
      unit.apply(
          new ManifestsUnitContext(
              chart,
              "tailscale",
              unit.manifestUnitId(),
              new Cdk8sApiObjectResolver(chart),
              POLICY,
              new DefaultNodeEnvContext(IDENTITY),
              new YamlMapper()));
    }
    final List<Map<String, Object>> found = new ArrayList<>();
    for (final Object document : chart.toJson()) {
      final Map<String, Object> d = (Map<String, Object>) document;
      if (kind.equals(d.get("kind"))) {
        found.add(d);
      }
    }
    return found;
  }

  @Test
  @SuppressWarnings("unchecked")
  void every_funnel_proxy_class_carries_the_declared_posture(@TempDir Path outdir) {
    final List<Map<String, Object>> classes =
        rendered(outdir, new FunnelStatePersistenceManifestsUnit(), "ProxyClass");
    assertEquals(FunnelLeaf.values().length, classes.size(), classes.toString());
    for (final Map<String, Object> proxyClass : classes) {
      assertEquals(
          FunnelCertIssuance.current().staging(),
          ((Map<String, Object>) proxyClass.get("spec")).get("useLetsEncryptStagingEnvironment"),
          proxyClass.toString());
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void the_chart_gets_no_value_it_does_not_read(@TempDir Path outdir) {
    final List<Map<String, Object>> charts =
        rendered(outdir, new TailscaleManifestsUnit(), "HelmChart");
    assertEquals(1, charts.size(), charts.toString());
    final String values =
        (String) ((Map<String, Object>) charts.get(0).get("spec")).get("valuesContent");
    assertFalse(values.contains("useLetsEncryptStagingEnvironment"), values);
    assertFalse(values.contains("debug: true"), values);
  }
}
