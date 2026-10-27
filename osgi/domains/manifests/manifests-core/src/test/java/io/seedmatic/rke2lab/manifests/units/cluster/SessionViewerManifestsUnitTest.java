package io.seedmatic.rke2lab.manifests.units.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.manifests.Cdk8sApiObjectResolver;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.YamlMapper;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainPolicy;
import io.seedmatic.rke2lab.manifests.contract.profiles.BootstrapIdentity;
import io.seedmatic.rke2lab.manifests.node.DefaultNodeEnvContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.cdk8s.App;
import org.cdk8s.Chart;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The sessions' viewer: a ServiceAccount whose role READS the cluster and nothing that holds or
 * reaches a secret. No Secret, and no read-through subresource (a pod's log, exec, attach, port
 * forward, a proxy), which would read what a container or a node holds.
 */
@Tag("osgi")
class SessionViewerManifestsUnitTest {

  private static final Set<String> READ = Set.of("get", "list", "watch");

  private static List<Map<String, Object>> render() {
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
    final ManifestDomainPolicy policy = ManifestDomainPolicy.builder().cluster(true).build();
    final Chart chart = new Chart(new App(), "manifests");
    final SessionViewerManifestsUnit unit = new SessionViewerManifestsUnit();
    unit.apply(
        new ManifestsUnitContext(
            chart,
            "cluster",
            unit.manifestUnitId(),
            new Cdk8sApiObjectResolver(chart),
            policy,
            new DefaultNodeEnvContext(identity),
            new YamlMapper()));
    @SuppressWarnings("unchecked")
    final List<Map<String, Object>> documents =
        chart.toJson().stream().map(d -> (Map<String, Object>) d).toList();
    return documents;
  }

  private static Map<String, Object> only(
      final List<Map<String, Object>> documents, final String kind) {
    final List<Map<String, Object>> found =
        documents.stream().filter(d -> kind.equals(d.get("kind"))).toList();
    assertEquals(1, found.size(), kind + ": " + found);
    return found.get(0);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> rules() {
    return (List<Map<String, Object>>) only(render(), "ClusterRole").get("rules");
  }

  @Test
  @SuppressWarnings("unchecked")
  void requireTheViewerReadsOnlyAndNeverASecretNorAReadThrough() {
    for (final Map<String, Object> rule : rules()) {
      final List<String> verbs = (List<String>) rule.get("verbs");
      assertTrue(READ.containsAll(verbs), "a verb that is not a read: " + rule);
      final List<String> groups = (List<String>) rule.get("apiGroups");
      assertFalse(groups.contains("*"), "a wildcard group serves aggregated APIs too: " + rule);
      for (final String resource : (List<String>) rule.get("resources")) {
        assertFalse("*".equals(resource) || resource.endsWith("/*"), "a wildcard: " + rule);
        assertFalse("secrets".equals(resource), "Secrets: " + rule);
        final int slash = resource.indexOf('/');
        if (slash >= 0) {
          final String sub = resource.substring(slash + 1);
          assertFalse(
              Set.of("log", "exec", "attach", "portforward", "proxy").contains(sub),
              "a read-through subresource " + resource);
        }
      }
      assertFalse(rule.containsKey("nonResourceURLs"), "a non-resource URL: " + rule);
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void requireTheViewerReadsWhatTheFleetRuns() {
    final Map<String, Set<String>> readable =
        rules().stream()
            .flatMap(
                rule ->
                    ((List<String>) rule.get("apiGroups"))
                        .stream()
                            .map(
                                group ->
                                    Map.entry(
                                        group, Set.copyOf((List<String>) rule.get("resources")))))
            .collect(
                Collectors.toMap(
                    Map.Entry::getKey,
                    Map.Entry::getValue,
                    (a, b) -> {
                      final java.util.HashSet<String> both = new java.util.HashSet<>(a);
                      both.addAll(b);
                      return both;
                    }));
    // What 🛡️'s DaemonSet read and a diagnosis need: the workloads, their pods and ConfigMaps,
    // the nodes, the CRDs, and our own custom resources.
    assertTrue(readable.get("").containsAll(Set.of("pods", "configmaps", "nodes", "events")));
    assertTrue(readable.get("apps").containsAll(Set.of("daemonsets", "deployments")));
    assertTrue(readable.get("apiextensions.k8s.io").contains("customresourcedefinitions"));
    assertTrue(readable.get("cluster.seedmatic.io").contains("*") == false);
    assertTrue(readable.get("cluster.seedmatic.io").contains("poolintentions"));
  }

  @Test
  @SuppressWarnings("unchecked")
  void requireTheViewerIsBoundAndHasItsLongLivedToken() {
    final List<Map<String, Object>> documents = render();
    final Map<String, Object> account = only(documents, "ServiceAccount");
    final Map<String, Object> binding = only(documents, "ClusterRoleBinding");
    final Map<String, Object> token = only(documents, "Secret");
    final Map<String, Object> accountMeta = (Map<String, Object>) account.get("metadata");
    assertEquals(SessionViewerManifestsUnit.ACCOUNT, accountMeta.get("name"));
    assertEquals("kube-system", accountMeta.get("namespace"));
    final Map<String, Object> roleRef = (Map<String, Object>) binding.get("roleRef");
    assertEquals(SessionViewerManifestsUnit.ACCOUNT, roleRef.get("name"));
    assertEquals(
        List.of(
            Map.of(
                "kind", "ServiceAccount",
                "name", SessionViewerManifestsUnit.ACCOUNT,
                "namespace", "kube-system")),
        binding.get("subjects"));
    // The token Secret is EMPTY as rendered: the token controller fills it in the cluster, so no
    // credential ever rides the branch.
    assertEquals("kubernetes.io/service-account-token", token.get("type"));
    assertFalse(token.containsKey("data"), "a rendered token");
    assertEquals(
        SessionViewerManifestsUnit.ACCOUNT,
        ((Map<String, Object>) ((Map<String, Object>) token.get("metadata")).get("annotations"))
            .get("kubernetes.io/service-account.name"));
  }
}
