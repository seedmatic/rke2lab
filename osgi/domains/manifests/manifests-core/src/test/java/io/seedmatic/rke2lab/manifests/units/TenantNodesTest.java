package io.seedmatic.rke2lab.manifests.units;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.manifests.Cdk8sApiObjectResolver;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsDomain;
import io.seedmatic.rke2lab.manifests.ManifestsDomainRegistrar;
import io.seedmatic.rke2lab.manifests.ManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.YamlMapper;
import io.seedmatic.rke2lab.manifests.contract.ClusterFleet;
import io.seedmatic.rke2lab.manifests.contract.ClusterRole;
import io.seedmatic.rke2lab.manifests.contract.ControlPlaneShape;
import io.seedmatic.rke2lab.manifests.contract.ManifestAnnotation;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainPolicy;
import io.seedmatic.rke2lab.manifests.contract.ManifestSynthesisRequest;
import io.seedmatic.rke2lab.manifests.contract.profiles.BootstrapIdentity;
import io.seedmatic.rke2lab.manifests.contract.profiles.ImageState;
import io.seedmatic.rke2lab.manifests.contract.profiles.NodeRuntime;
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
import io.seedmatic.rke2lab.manifests.domain.VclusterDomainRegistrar;
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
    // A workload cluster's real render: cluster-api's operator is MGMT-only, so 13 pod templates.
    assertTrue(pods >= 13, "the render lost pod templates: " + pods);
    assertEquals(List.of(), tolerating, "fleet pods that would land on a tenant's node");
  }

  /**
   * The session viewer reads ConfigMaps and pod specs (🛡️, 2026-10-27), on the rule that the
   * render keeps every secret in a Secret. This holds the render to it: no ConfigMap value, no
   * HelmChart or HelmChartConfig valuesContent and no container env value carries a secret's shape.
   * An image digest ({@code @sha256:<hex>}) is not one.
   */
  @Test
  void requireNoConfigMapNorPodEnvCarriesASecretsShape(@TempDir Path outdir) {
    final java.util.regex.Pattern shape =
        java.util.regex.Pattern.compile(
            "-----BEGIN [A-Z ]*PRIVATE KEY-----"
                + "|AGE-SECRET-KEY-1[0-9A-Z]{20,}"
                + "|\\bgh[pousr]_[A-Za-z0-9]{30,}"
                + "|\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\."
                + "|(?<!sha256:)\\b[0-9a-f]{64}\\b"
                + "|(?i)(password|passwd|secret|token)\\s*[:=]\\s*[^\\s${}<>]{8,}");
    final List<String> found = new ArrayList<>();
    for (final Object doc : renderEveryUnit(outdir)) {
      final Map<?, ?> document = (Map<?, ?>) doc;
      final String name =
          document.get("kind") + "/" + ((Map<?, ?>) document.get("metadata")).get("name");
      if ("ConfigMap".equals(document.get("kind"))
          && document.get("data") instanceof Map<?, ?> data) {
        data.forEach(
            (key, value) -> {
              if (shape.matcher(String.valueOf(value)).find()) {
                found.add(name + " data." + key);
              }
            });
      }
      if (("HelmChart".equals(document.get("kind"))
              || "HelmChartConfig".equals(document.get("kind")))
          && document.get("spec") instanceof Map<?, ?> helm
          && helm.get("valuesContent") != null
          && shape.matcher(String.valueOf(helm.get("valuesContent"))).find()) {
        found.add(name + " spec.valuesContent");
      }
      if (!POD_KINDS.contains(document.get("kind"))) {
        continue;
      }
      final Map<?, ?> spec = (Map<?, ?>) document.get("spec");
      final Map<?, ?> podSpec =
          "Pod".equals(document.get("kind"))
              ? spec
              : (Map<?, ?>) ((Map<?, ?>) spec.get("template")).get("spec");
      for (final String field : List.of("containers", "initContainers")) {
        final Object containers = podSpec.get(field);
        if (containers == null) {
          continue;
        }
        for (final Object raw :
            containers instanceof Object[] array ? List.of(array) : (Iterable<?>) containers) {
          final Object env = ((Map<?, ?>) raw).get("env");
          if (env == null) {
            continue;
          }
          for (final Object var :
              env instanceof Object[] array ? List.of(array) : (Iterable<?>) env) {
            final Object value = ((Map<?, ?>) var).get("value");
            if (value != null && shape.matcher(String.valueOf(value)).find()) {
              found.add(name + " env." + ((Map<?, ?>) var).get("name"));
            }
          }
        }
      }
    }
    assertEquals(List.of(), found, "secret-shaped values outside a Secret");
  }

  /**
   * Every LoadBalancer is announced by exactly ONE L2 policy: a fleet one by the « host » policy, a
   * vcluster's API by its own, from the tenant's pool. Two policies on one Service would race for
   * its lease; none would leave it unreachable.
   *
   * <p>The fleet's own LoadBalancers are born at RUNTIME, from their charts' values (cilium's
   * clustermesh apiserver and ingress, Envoy Gateway's proxies), so the render cannot list them; a
   * representative unlabelled one stands in for them, and the live read after the cold start checks
   * the real set.
   */
  @Test
  @SuppressWarnings("unchecked")
  void requireEveryLoadBalancerIsAnnouncedByExactlyOnePolicy(@TempDir Path outdir) {
    final List<Map<?, ?>> documents =
        renderEveryUnit(outdir).stream().<Map<?, ?>>map(d -> (Map<?, ?>) d).toList();
    final List<Map<?, ?>> policies =
        documents.stream().filter(d -> "CiliumL2AnnouncementPolicy".equals(d.get("kind"))).toList();
    final List<Map<?, ?>> loadBalancers = new ArrayList<>();
    documents.stream()
        .filter(d -> "Service".equals(d.get("kind")))
        .filter(d -> "LoadBalancer".equals(((Map<?, ?>) d.get("spec")).get("type")))
        .forEach(loadBalancers::add);
    assertEquals(1, loadBalancers.size(), "the vcluster's API, the render's one LoadBalancer");
    loadBalancers.add(
        Map.of(
            "metadata",
            Map.of(
                "name",
                "cilium-ingress",
                "namespace",
                "kube-system",
                "labels",
                Map.of("io.cilium/lb-ipam-pool", "fabric"))));
    final List<String> wrong = new ArrayList<>();
    for (final Map<?, ?> service : loadBalancers) {
      final Map<?, ?> metadata = (Map<?, ?>) service.get("metadata");
      final Map<?, ?> labels = metadata.get("labels") instanceof Map<?, ?> m ? m : Map.of();
      final List<String> selecting =
          policies.stream()
              .filter(
                  policy ->
                      selects(
                          (Map<?, ?>) ((Map<?, ?>) policy.get("spec")).get("serviceSelector"),
                          labels))
              .map(policy -> String.valueOf(((Map<?, ?>) policy.get("metadata")).get("name")))
              .toList();
      final String expected =
          labels.containsKey(ManifestAnnotation.VCLUSTER_API_LABEL.key())
              ? "vcluster-" + labels.get(ManifestAnnotation.VCLUSTER_API_LABEL.key()) + "-api"
              : "host";
      if (!selecting.equals(List.of(expected))) {
        wrong.add(
            metadata.get("namespace") + "/" + metadata.get("name") + " selected by " + selecting);
      }
    }
    assertEquals(List.of(), wrong);
  }

  /** A label selector's match, for matchLabels and the In / NotIn / Exists / DoesNotExist set. */
  private static boolean selects(final Map<?, ?> selector, final Map<?, ?> labels) {
    if (selector == null) {
      return true;
    }
    if (selector.get("matchLabels") instanceof Map<?, ?> match) {
      for (final Map.Entry<?, ?> e : match.entrySet()) {
        if (!e.getValue().equals(labels.get(e.getKey()))) {
          return false;
        }
      }
    }
    final Object expressions = selector.get("matchExpressions");
    if (expressions != null) {
      for (final Object raw :
          expressions instanceof Object[] array ? List.of(array) : (Iterable<?>) expressions) {
        final Map<?, ?> expression = (Map<?, ?>) raw;
        final Object key = expression.get("key");
        final Object values = expression.get("values");
        final List<?> set =
            values == null
                ? List.of()
                : values instanceof Object[] array ? List.of(array) : List.copyOf((List<?>) values);
        final boolean ok =
            switch (String.valueOf(expression.get("operator"))) {
              case "In" -> set.contains(labels.get(key));
              case "NotIn" -> !labels.containsKey(key) || !set.contains(labels.get(key));
              case "Exists" -> labels.containsKey(key);
              case "DoesNotExist" -> !labels.containsKey(key);
              default -> false;
            };
        if (!ok) {
          return false;
        }
      }
    }
    return true;
  }

  /**
   * ccr's vcluster lives on nikopol-wrkld, a WORKLOAD cluster: its real policy must render it, and
   * a management cluster's must render none of it (enabling the domain on both roles leaks
   * nothing).
   */
  @Test
  void requireTheVclusterRendersOnItsWorkloadHostAndNowhereElse(@TempDir Path outdir) {
    final List<String> vclusterKinds =
        List.of("HelmChart", "MutatingAdmissionPolicy", "MutatingAdmissionPolicyBinding");
    final java.util.function.Function<List<Object>, List<String>> vclusterObjects =
        documents ->
            documents.stream()
                .map(d -> (Map<?, ?>) d)
                .filter(
                    d ->
                        vclusterKinds.contains(d.get("kind"))
                            && String.valueOf(((Map<?, ?>) d.get("metadata")).get("name"))
                                .contains("ccr"))
                .map(d -> d.get("kind") + "/" + ((Map<?, ?>) d.get("metadata")).get("name"))
                .sorted()
                .toList();
    assertEquals(
        List.of(
            "HelmChart/ccr",
            "MutatingAdmissionPolicy/vcluster-ccr-pin-to-pool",
            "MutatingAdmissionPolicyBinding/vcluster-ccr-pin-to-pool"),
        vclusterObjects.apply(render(outdir, "nikopol-wrkld", ClusterRole.WRKLD)));
    assertEquals(
        List.of(),
        vclusterObjects.apply(render(outdir.resolve("mgmt"), "nikopol-mgmt", ClusterRole.MGMT)));
  }

  /**
   * No FloxEnv installs a FloxHub catalog package ({@code pkg-path}): resolving one now requires a
   * FloxHub login, which no node has (measured 2026-10-28: the five envs with one failed, the two
   * with only {@code floxcatalog:} flakes realised). Every install comes from our own catalog.
   */
  @Test
  void requireNoFloxEnvResolvesAgainstFloxHub(@TempDir Path outdir) {
    final List<String> fromFloxHub = new ArrayList<>();
    for (final ClusterRole role : ClusterRole.values()) {
      final String cluster = role == ClusterRole.MGMT ? "nikopol-mgmt" : "nikopol-wrkld";
      for (final Object doc : render(outdir.resolve(cluster), cluster, role)) {
        final Map<?, ?> document = (Map<?, ?>) doc;
        if (!"FloxEnv".equals(document.get("kind"))) {
          continue;
        }
        final Map<?, ?> manifest = (Map<?, ?>) ((Map<?, ?>) document.get("spec")).get("manifest");
        final Map<?, ?> install = (Map<?, ?>) manifest.get("install");
        install.forEach(
            (name, entry) -> {
              if (((Map<?, ?>) entry).containsKey("pkg-path")) {
                fromFloxHub.add(((Map<?, ?>) document.get("metadata")).get("name") + "/" + name);
              }
            });
      }
    }
    assertEquals(List.of(), fromFloxHub.stream().distinct().sorted().toList());
  }

  /**
   * flox-controller reads the FloxHub token from its Secret by NAME, optional (a cluster without it
   * still realises its catalog-only envs), and never carries a value; the Secret reaches
   * rke2lab-system as a replica of the source the operator's grow seals.
   */
  @Test
  void requireFloxControllerReadsTheFloxhubTokenFromItsSecretOnly(@TempDir Path outdir) {
    final List<Map<?, ?>> documents =
        render(outdir, "nikopol-wrkld", ClusterRole.WRKLD).stream()
            .<Map<?, ?>>map(d -> (Map<?, ?>) d)
            .toList();
    final Map<?, ?> daemonSet =
        documents.stream()
            .filter(d -> "DaemonSet".equals(d.get("kind")))
            .filter(d -> "flox-controller".equals(((Map<?, ?>) d.get("metadata")).get("name")))
            .findFirst()
            .orElseThrow();
    final Map<?, ?> podSpec =
        (Map<?, ?>) ((Map<?, ?>) ((Map<?, ?>) daemonSet.get("spec")).get("template")).get("spec");
    final Object containers = podSpec.get("containers");
    final List<?> list =
        containers instanceof Object[] array ? List.of(array) : (List<?>) containers;
    final Object env = ((Map<?, ?>) list.get(0)).get("env");
    final List<?> vars = env instanceof Object[] array ? List.of(array) : (List<?>) env;
    final Map<?, ?> token =
        vars.stream()
            .map(v -> (Map<?, ?>) v)
            .filter(v -> "FLOX_FLOXHUB_TOKEN".equals(v.get("name")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no FLOX_FLOXHUB_TOKEN in " + vars));
    assertFalse(token.containsKey("value"), "an inline token value");
    assertEquals(
        Map.of("secretKeyRef", Map.of("name", "floxhub-token", "key", "token", "optional", true)),
        token.get("valueFrom"));
    assertTrue(
        documents.stream()
            .filter(d -> "Secret".equals(d.get("kind")))
            .map(d -> (Map<?, ?>) d.get("metadata"))
            .anyMatch(
                m ->
                    "floxhub-token".equals(m.get("name"))
                        && "rke2lab-system".equals(m.get("namespace"))
                        && m.get("annotations") instanceof Map<?, ?> a
                        && "rke2lab-secrets/floxhub-token"
                            .equals(a.get("replicator.v1.mittwald.de/replicate-from"))),
        "no floxhub-token replica stub in rke2lab-system");
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
    return render(outdir, "nikopol-wrkld", ClusterRole.WRKLD);
  }

  /**
   * The render a cluster of {@code role} actually gets: the registrars filtered by its REAL domain
   * policy plus their declared domain dependencies, as DefaultManifestSynthesisService does. An
   * everything-on policy hid that ccr's vcluster sat in a domain no workload cluster renders.
   */
  private static List<Object> render(
      final Path outdir, final String cluster, final ClusterRole role) {
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
            new TailscaleDomainRegistrar(),
            new VclusterDomainRegistrar());
    final ManifestDomainCatalog catalog =
        ManifestDomainCatalog.builder().addDefaultDomains().build();
    final ManifestDomainPolicy policy = role.domainPolicy(catalog);
    final Map<String, ManifestsDomain> byId = new java.util.LinkedHashMap<>();
    registrars.forEach(r -> byId.put(r.domain().domainId(), r.domain()));
    final java.util.Set<String> effective = new java.util.LinkedHashSet<>();
    final java.util.ArrayDeque<String> pending =
        new java.util.ArrayDeque<>(policy.enabledDomainIds());
    while (!pending.isEmpty()) {
      final String id = pending.pop();
      if (effective.add(id) && byId.containsKey(id)) {
        pending.addAll(byId.get(id).dependsOnDomainIds());
      }
    }
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
    final App app = new App(AppProps.builder().outdir(outdir.toString()).build());
    final Chart chart = new Chart(app, "manifests");
    final ManifestSynthesisRequest request =
        ManifestSynthesisRequest.builder(outdir, outdir.resolve("manifests.yaml"))
            .bootstrapIdentity(identity)
            // A 1.36 server image, so the vcluster (rendered only where its pin is served) is in.
            .imageState(
                Optional.of(
                    new ImageState(
                        "node-server",
                        "f".repeat(64),
                        "sha256-buildinputs",
                        "rke2lab",
                        "https://nixos.nikopol:8443",
                        "1.36.4+rke2r1",
                        new NodeRuntime(
                            List.of("ip_vs"), "lxc.mount.auto = proc:rw", true, true, true, true))))
            .clusterFleet(
                Optional.of(
                    new ClusterFleet(
                        List.of("bioskop", "nikopol"), "bioskop", ControlPlaneShape.HA)))
            .manifestDomainPolicy(Optional.of(policy))
            .fabricBridgeParent(Optional.of("fabric-br"))
            .build();
    try (var bound = ManifestSynthesisContext.of(request).bind()) {
      for (final ManifestsDomainRegistrar registrar : registrars) {
        if (!effective.contains(registrar.domain().domainId())) {
          continue;
        }
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
