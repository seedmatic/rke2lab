package io.seedmatic.rke2lab.manifests.units.tailscale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.Cdk8sApiObjectResolver;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.YamlMapper;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainPolicy;
import io.seedmatic.rke2lab.manifests.contract.ManifestSynthesisRequest;
import io.seedmatic.rke2lab.manifests.contract.profiles.BootstrapIdentity;
import io.seedmatic.rke2lab.manifests.ingress.Funnel;
import io.seedmatic.rke2lab.manifests.ingress.FunnelLeaf;
import io.seedmatic.rke2lab.manifests.node.DefaultNodeEnvContext;
import io.seedmatic.rke2lab.manifests.units.cicd.PacWebhookManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.gitops.FluxReceiverManifestsUnit;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.cdk8s.App;
import org.cdk8s.AppProps;
import org.cdk8s.Chart;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every public door is encrypted: each {@link FunnelLeaf} renders exactly one Funnel Ingress, and
 * that Ingress is served by the Tailscale class with TLS for its own MagicDNS name, which the
 * operator terminates with its Let's Encrypt cert. The rke2lab side of ndh's {@code
 * checks.endpoint-exposure} (public ⇒ encrypted), held where the Funnels are born.
 */
@Tag("osgi")
class FunnelExposureRenderTest {

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
  private static List<Map<String, Object>> ingresses(
      final Path outdir, final AbstractManifestsUnit unit) {
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
              "funnel",
              unit.manifestUnitId(),
              new Cdk8sApiObjectResolver(chart),
              POLICY,
              new DefaultNodeEnvContext(IDENTITY),
              new YamlMapper()));
    }
    final List<Map<String, Object>> found = new ArrayList<>();
    for (final Object document : chart.toJson()) {
      final Map<String, Object> d = (Map<String, Object>) document;
      final Map<String, Object> annotations =
          (Map<String, Object>)
              ((Map<String, Object>) d.getOrDefault("metadata", Map.of()))
                  .getOrDefault("annotations", Map.of());
      if ("Ingress".equals(d.get("kind"))
          && "true".equals(annotations.get("tailscale.com/funnel"))) {
        found.add(d);
      }
    }
    return found;
  }

  /**
   * The TLS hosts of an Ingress the Tailscale class serves; empty when it is another class or has
   * no TLS.
   */
  @SuppressWarnings("unchecked")
  private static Set<String> tlsHosts(final Map<String, Object> ingress) {
    final Map<String, Object> spec = (Map<String, Object>) ingress.get("spec");
    if (spec == null || !"tailscale".equals(spec.get("ingressClassName"))) {
      return Set.of();
    }
    if (!(spec.get("tls") instanceof List<?> entries)) {
      return Set.of();
    }
    return entries.stream()
        .map(e -> (List<Object>) ((Map<String, Object>) e).get("hosts"))
        .filter(hosts -> hosts != null)
        .flatMap(List::stream)
        .map(Object::toString)
        .collect(Collectors.toSet());
  }

  private static List<Map<String, Object>> allFunnelIngresses(final Path outdir) {
    final List<Map<String, Object>> all = new ArrayList<>();
    all.addAll(ingresses(outdir.resolve("pac"), new PacWebhookManifestsUnit()));
    all.addAll(ingresses(outdir.resolve("flux"), new FluxReceiverManifestsUnit()));
    return all;
  }

  @Test
  @SuppressWarnings("unchecked")
  void every_funnel_leaf_renders_one_encrypted_ingress(@TempDir Path outdir) {
    final List<Map<String, Object>> found = allFunnelIngresses(outdir);
    assertEquals(FunnelLeaf.values().length, found.size(), "one Funnel Ingress per leaf: " + found);
    // Each public door is served with TLS for its funnel's own MagicDNS name; the object's name is
    // cluster-local.
    final Set<String> served =
        found.stream().flatMap(i -> tlsHosts(i).stream()).collect(Collectors.toSet());
    final Set<String> expected =
        java.util.Arrays.stream(FunnelLeaf.values())
            .map(leaf -> Funnel.of(IDENTITY.clusterName(), leaf).hostname())
            .collect(Collectors.toSet());
    assertEquals(expected, served, "a Funnel served without TLS for its own name");
  }

  @Test
  @SuppressWarnings("unchecked")
  void mutant_a_funnel_ingress_without_tls_is_refused(@TempDir Path outdir) {
    final Map<String, Object> rendered = allFunnelIngresses(outdir).get(0);
    final Map<String, Object> mutant = new HashMap<>(rendered);
    final Map<String, Object> spec = new HashMap<>((Map<String, Object>) rendered.get("spec"));
    spec.remove("tls");
    mutant.put("spec", spec);
    assertTrue(!tlsHosts(rendered).isEmpty(), "the control must pass");
    assertTrue(tlsHosts(mutant).isEmpty(), "a Funnel Ingress without TLS passed the rule");
  }
}
