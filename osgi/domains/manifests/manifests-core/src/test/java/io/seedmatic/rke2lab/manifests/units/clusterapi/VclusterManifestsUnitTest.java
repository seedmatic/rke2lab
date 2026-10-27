package io.seedmatic.rke2lab.manifests.units.clusterapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.manifests.Cdk8sApiObjectResolver;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.YamlMapper;
import io.seedmatic.rke2lab.manifests.contract.ManifestAnnotation;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainPolicy;
import io.seedmatic.rke2lab.manifests.contract.ManifestSynthesisRequest;
import io.seedmatic.rke2lab.manifests.contract.profiles.BootstrapIdentity;
import io.seedmatic.rke2lab.manifests.contract.profiles.ImageState;
import io.seedmatic.rke2lab.manifests.contract.profiles.NodeRuntime;
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

  private static ImageState image(final String rke2Version) {
    return new ImageState(
        "node-base",
        "f".repeat(64),
        "sha256-buildinputs",
        "rke2lab",
        "https://nixos.nikopol:8443",
        rke2Version,
        new NodeRuntime(List.of("ip_vs"), "lxc.mount.auto = proc:rw", true, true, true, true));
  }

  private static List<Map<String, Object>> render(final Path outdir, final String cluster) {
    return render(outdir, cluster, Optional.of(image("1.36.4+rke2r1")));
  }

  private static List<Map<String, Object>> render(
      final Path outdir, final String cluster, final Optional<ImageState> image) {
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
            .imageState(image)
            .manifestDomainPolicy(Optional.of(policy))
            .build();
    try (var bound = ManifestSynthesisContext.of(request).bind()) {
      for (final ManifestsUnit unit :
          List.<ManifestsUnit>of(new VclusterPinManifestsUnit(), new VclusterManifestsUnit())) {
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
  void requireNothingWhereThePinIsNotServed(@TempDir Path outdir) {
    // MutatingAdmissionPolicy v1 is served from 1.36: below it, or with no image to read the
    // version from, the synced pods would carry the toleration with no nodeSelector, free to land
    // on fleet nodes. So neither the pin nor the vcluster.
    assertEquals(List.of(), render(outdir, "nikopol-wrkld", Optional.of(image("1.34.11+rke2r1"))));
    assertEquals(List.of(), render(outdir, "nikopol-wrkld", Optional.of(image("1.35.8+rke2r1"))));
    assertEquals(List.of(), render(outdir, "nikopol-wrkld", Optional.empty()));
    assertFalse(render(outdir, "nikopol-wrkld", Optional.of(image("1.37.0+rke2r1"))).isEmpty());
  }

  @Test
  void requireTheVclusterWaitsOnItsPin() {
    // Its own unit, so Flux applies the vcluster's cell only after the policy's.
    assertTrue(
        new VclusterManifestsUnit()
            .dependsOnManifestsUnitIds()
            .contains(VclusterPinManifestsUnit.MANIFEST_UNIT_ID));
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

  @Test
  @SuppressWarnings("unchecked")
  void requireTheApiIsAnnouncedFromCcrsPoolOnly(@TempDir Path outdir) {
    final List<Map<String, Object>> policies =
        of(render(outdir, "nikopol-wrkld"), "CiliumL2AnnouncementPolicy");
    assertEquals(1, policies.size());
    final Map<String, Object> spec = at(policies.get(0), "spec");
    // Local traffic: the announcing node must host the API pod, and the pod is on ccr's pool.
    assertEquals(
        Map.of(
            "matchExpressions",
            List.of(Map.of("key", "project", "operator", "In", "values", List.of("ccr")))),
        spec.get("nodeSelector"));
    assertEquals(
        Map.of("matchLabels", Map.of(ManifestAnnotation.VCLUSTER_API_LABEL.key(), "ccr")),
        spec.get("serviceSelector"));
    assertEquals(List.of("fabric0"), spec.get("interfaces"));
    assertEquals(true, spec.get("loadBalancerIPs"));
    final Map<String, Object> service =
        of(render(outdir, "nikopol-wrkld"), "Service").stream()
            .filter(s -> "LoadBalancer".equals(at(s, "spec").get("type")))
            .findFirst()
            .orElseThrow();
    assertEquals(
        "ccr", at(service, "metadata", "labels").get(ManifestAnnotation.VCLUSTER_API_LABEL.key()));
  }

  @Test
  @SuppressWarnings("unchecked")
  void requireTheQuotaRefusesAnyOtherLoadBalancerAndEveryNodePort(@TempDir Path outdir) {
    final Map<String, Object> resourceQuota =
        at(values(render(outdir, "nikopol-wrkld")), "policies", "resourceQuota");
    assertEquals(true, resourceQuota.get("enabled"));
    final Map<String, Object> quota = (Map<String, Object>) resourceQuota.get("quota");
    // The ONE LoadBalancer is the render's API: a synced Service can take no other, nor a port.
    assertEquals(0, quota.get("services.nodeports"));
    assertEquals(1, quota.get("services.loadbalancers"));
    assertEquals(
        true, at(values(render(outdir, "nikopol-wrkld")), "policies", "limitRange").get("enabled"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void requireTheNamespaceEnforcesRestrictedPodSecurity(@TempDir Path outdir) {
    final Map<String, Object> labels =
        at(of(render(outdir, "nikopol-wrkld"), "Namespace").get(0), "metadata", "labels");
    assertEquals("restricted", labels.get("pod-security.kubernetes.io/enforce"));
    assertEquals("restricted", labels.get("pod-security.kubernetes.io/warn"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void requireTheControlPlaneRunsRootlessUnderRestricted(@TempDir Path outdir) {
    // The chart's default runs as root and fails `restricted` on 8 points (measured offline:
    // helm template 0.37.4 + a PSS check). Its rootless mode, set at FIRST deployment (no
    // migration from root), passes, with the init container's own context beside it.
    final Map<String, Object> values = values(render(outdir, "nikopol-wrkld"));
    final Map<String, Object> restricted =
        Map.of(
            "allowPrivilegeEscalation",
            false,
            "runAsNonRoot",
            true,
            "runAsUser",
            12345,
            "runAsGroup",
            12345,
            "capabilities",
            Map.of("drop", List.of("ALL")),
            "seccompProfile",
            Map.of("type", "RuntimeDefault"));
    final Map<String, Object> security = at(values, "controlPlane", "statefulSet", "security");
    assertEquals(restricted, security.get("containerSecurityContext"));
    assertEquals(
        Map.of(
            "runAsNonRoot", true,
            "runAsUser", 12345,
            "runAsGroup", 12345,
            "fsGroup", 12345,
            "seccompProfile", Map.of("type", "RuntimeDefault")),
        security.get("podSecurityContext"));
    assertEquals(restricted, at(values, "controlPlane", "distro", "k8s").get("securityContext"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void requireTheApiAdmitsNoSourceOutsideTheTenantUntilTheSeatsIsMeasured(@TempDir Path outdir) {
    final List<Map<String, Object>> policies =
        of(render(outdir, "nikopol-wrkld"), "CiliumNetworkPolicy");
    assertEquals(1, policies.size());
    final Map<String, Object> spec = at(policies.get(0), "spec");
    assertEquals(
        Map.of("matchLabels", Map.of("app", "vcluster", "release", "ccr")),
        spec.get("endpointSelector"));
    final List<Map<String, Object>> ingress = (List<Map<String, Object>>) spec.get("ingress");
    assertEquals(1, ingress.size());
    // Fail closed: only the tenant's own pods reach the API. No fromCIDR, no fromEntities, until
    // the address the seat's flows carry is measured (behind the tailnet's SNAT).
    final Map<String, Object> rule = ingress.get(0);
    assertFalse(rule.containsKey("fromCIDR"));
    assertFalse(rule.containsKey("fromCIDRSet"));
    assertFalse(rule.containsKey("fromEntities"));
    assertEquals(
        List.of(Map.of("matchLabels", Map.of("k8s:io.kubernetes.pod.namespace", "vcluster-ccr"))),
        rule.get("fromEndpoints"));
  }
}
