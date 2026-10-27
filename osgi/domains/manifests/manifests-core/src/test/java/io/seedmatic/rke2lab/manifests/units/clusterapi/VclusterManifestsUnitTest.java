package io.seedmatic.rke2lab.manifests.units.clusterapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.manifests.Cdk8sApiObjectResolver;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.YamlMapper;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainPolicy;
import io.seedmatic.rke2lab.manifests.contract.ManifestSynthesisRequest;
import io.seedmatic.rke2lab.manifests.contract.profiles.BootstrapIdentity;
import io.seedmatic.rke2lab.manifests.node.DefaultNodeEnvContext;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.cdk8s.App;
import org.cdk8s.Chart;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

/**
 * ccr's vcluster, on its host's branch: a namespace, the loft chart, the API LoadBalancer on the
 * fabric, and the policy that pins the tenant's pods to its own pool. Its pods never land on a
 * fleet node, and nothing of it reaches another cluster's branch.
 */
@Tag("osgi")
class VclusterManifestsUnitTest {

  private static List<Map<String, Object>> render(final Path outdir, final String cluster) {
    final BootstrapIdentity identity =
        new BootstrapIdentity(
            cluster,
            5,
            "token",
            "cluster.local",
            "dev",
            "master",
            1,
            "workload",
            "nikopol-nixos",
            cluster + "-master",
            cluster + "-master.nikopol");
    final ManifestDomainPolicy policy = ManifestDomainPolicy.builder().clusterApi(true).build();
    final Chart chart = new Chart(new App(), "manifests");
    final ManifestSynthesisRequest request =
        ManifestSynthesisRequest.builder(outdir, outdir.resolve("manifests.yaml"))
            .bootstrapIdentity(identity)
            .manifestDomainPolicy(Optional.of(policy))
            .build();
    final VclusterManifestsUnit unit = new VclusterManifestsUnit();
    try (var bound = ManifestSynthesisContext.of(request).bind()) {
      unit.apply(
          new ManifestsUnitContext(
              chart,
              "cluster-api",
              unit.manifestUnitId(),
              new Cdk8sApiObjectResolver(chart),
              policy,
              new DefaultNodeEnvContext(identity),
              new YamlMapper()));
    }
    // Every unit emits its group marker (a local-config ConfigMap, never applied); the rest is what
    // it renders.
    @SuppressWarnings("unchecked")
    final List<Map<String, Object>> documents =
        chart.toJson().stream()
            .map(d -> (Map<String, Object>) d)
            .filter(
                d ->
                    !"true"
                        .equals(
                            ((Map<String, Object>)
                                    ((Map<String, Object>) d.get("metadata")).get("annotations"))
                                .get("config.kubernetes.io/local-config")))
            .toList();
    return documents;
  }

  private static List<Map<String, Object>> of(
      final List<Map<String, Object>> documents, final String kind) {
    return documents.stream().filter(d -> kind.equals(d.get("kind"))).toList();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> at(final Map<String, Object> map, final String... path) {
    Map<String, Object> cursor = map;
    for (final String key : path) {
      cursor = (Map<String, Object>) cursor.get(key);
    }
    return cursor;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> values(final List<Map<String, Object>> documents) {
    final Map<String, Object> chart = of(documents, "HelmChart").get(0);
    return new Yaml().load(String.valueOf(at(chart, "spec").get("valuesContent")));
  }

  @Test
  void requireOnlyTheHostRendersTheVcluster(@TempDir Path outdir) {
    assertEquals(List.of(), render(outdir, "bioskop-wrkld"));
    assertEquals(List.of(), render(outdir, "nikopol-mgmt"));
    assertFalse(render(outdir, "nikopol-wrkld").isEmpty());
  }

  @Test
  void requireTheChartIsLoftsPinnedAndInstalledInTheTenantsNamespace(@TempDir Path outdir) {
    final List<Map<String, Object>> documents = render(outdir, "nikopol-wrkld");
    assertEquals(1, of(documents, "Namespace").size());
    assertEquals("vcluster-ccr", at(of(documents, "Namespace").get(0), "metadata").get("name"));
    final List<Map<String, Object>> charts = of(documents, "HelmChart");
    assertEquals(1, charts.size());
    final Map<String, Object> spec = at(charts.get(0), "spec");
    assertEquals("https://charts.loft.sh", spec.get("repo"));
    assertEquals("vcluster", spec.get("chart"));
    assertEquals("0.37.4", spec.get("version"));
    assertEquals("vcluster-ccr", spec.get("targetNamespace"));
    assertEquals("ccr", at(charts.get(0), "metadata").get("name"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void requireTheControlPlaneAndTheSyncedPodsStayOnCcrsPool(@TempDir Path outdir) {
    final Map<String, Object> values = values(render(outdir, "nikopol-wrkld"));
    final Map<String, Object> statefulSet = at(values, "controlPlane", "statefulSet", "scheduling");
    assertEquals(Map.of("project", "ccr"), statefulSet.get("nodeSelector"));
    assertEquals(
        List.of(
            Map.of("key", "project", "operator", "Equal", "value", "ccr", "effect", "NoSchedule")),
        statefulSet.get("tolerations"));
    assertEquals(
        List.of("project=ccr:NoSchedule"),
        at(values, "sync", "toHost", "pods").get("enforceTolerations"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void requireTheApiIsServedOnTheFabricAtItsComputedAddress(@TempDir Path outdir) {
    final List<Map<String, Object>> documents = render(outdir, "nikopol-wrkld");
    final Map<String, Object> values = values(documents);
    // The chart's own Service stays ClusterIP: the ONE LoadBalancer is the render's.
    assertEquals("ClusterIP", at(values, "controlPlane", "service", "spec").get("type"));
    final List<Map<String, Object>> loadBalancers =
        of(documents, "Service").stream()
            .filter(s -> "LoadBalancer".equals(at(s, "spec").get("type")))
            .toList();
    assertEquals(1, loadBalancers.size());
    final Map<String, Object> service = loadBalancers.get(0);
    assertEquals("vcluster-ccr", at(service, "metadata").get("namespace"));
    final Map<String, Object> labels = at(service, "metadata", "labels");
    assertEquals("fabric", labels.get("io.cilium/lb-ipam-pool"));
    assertEquals(
        "172.16.18.67", at(service, "metadata", "annotations").get("lbipam.cilium.io/ips"));
    assertEquals("Local", at(service, "spec").get("externalTrafficPolicy"));
    assertEquals(Map.of("app", "vcluster", "release", "ccr"), at(service, "spec").get("selector"));
    assertEquals("https://172.16.18.67:443", at(values, "exportKubeConfig").get("server"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void requireEveryPodOfTheTenantsNamespaceIsPinnedToItsPool(@TempDir Path outdir) {
    final List<Map<String, Object>> documents = render(outdir, "nikopol-wrkld");
    final List<Map<String, Object>> policies = of(documents, "MutatingAdmissionPolicy");
    final List<Map<String, Object>> bindings = of(documents, "MutatingAdmissionPolicyBinding");
    assertEquals(1, policies.size());
    assertEquals(1, bindings.size());
    assertEquals("admissionregistration.k8s.io/v1", policies.get(0).get("apiVersion"));
    final Map<String, Object> spec = at(policies.get(0), "spec");
    assertEquals("Fail", spec.get("failurePolicy"));
    final String mutation = String.valueOf(spec.get("mutations"));
    assertTrue(mutation.contains("nodeSelector"), mutation);
    assertTrue(mutation.contains("project"), mutation);
    final Map<String, Object> match = at(bindings.get(0), "spec", "matchResources");
    assertEquals(
        Map.of("matchLabels", Map.of("kubernetes.io/metadata.name", "vcluster-ccr")),
        match.get("namespaceSelector"));
  }
}
