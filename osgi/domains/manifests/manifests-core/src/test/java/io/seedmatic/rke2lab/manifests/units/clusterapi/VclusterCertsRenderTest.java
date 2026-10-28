package io.seedmatic.rke2lab.manifests.units.clusterapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.clusterpki.contract.AdminCredentials;
import io.seedmatic.rke2lab.clusterpki.contract.VclusterPki;
import io.seedmatic.rke2lab.manifests.Cdk8sApiObjectResolver;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.YamlMapper;
import io.seedmatic.rke2lab.manifests.contract.ClusterRole;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainPolicy;
import io.seedmatic.rke2lab.manifests.contract.ManifestSynthesisRequest;
import io.seedmatic.rke2lab.manifests.contract.profiles.BootstrapIdentity;
import io.seedmatic.rke2lab.manifests.domain.VclusterDomainRegistrar;
import io.seedmatic.rke2lab.manifests.node.DefaultNodeEnvContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.cdk8s.App;
import org.cdk8s.AppProps;
import org.cdk8s.Chart;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ccr's {@code ccr-certs} Secret: rendered by its HOST cluster's plane (nikopol-wrkld) in {@code
 * vcluster-ccr}, by no other plane, only with the material, holding exactly the roots vcluster
 * reads by name. Placeholders only: the unit copies what it embeds, it parses none of it.
 */
@Tag("osgi")
class VclusterCertsRenderTest {

  private static final ManifestDomainPolicy POLICY =
      ClusterRole.WRKLD.domainPolicy(ManifestDomainCatalog.builder().addDefaultDomains().build());

  private static VclusterPki.Pair pair(String tag) {
    return new VclusterPki.Pair("FAKE-" + tag + "-CERT", "FAKE-" + tag + "-KEY");
  }

  private static final VclusterPki PKI =
      new VclusterPki(
          List.of(
              new VclusterPki.Entry(
                  "ccr",
                  pair("CA"),
                  pair("SERVER-CA"),
                  pair("CLIENT-CA"),
                  pair("FRONT-PROXY-CA"),
                  pair("ETCD-CA"),
                  "FAKE-SA-KEY",
                  "FAKE-SA-PUB",
                  new AdminCredentials(
                      "FAKE-ADMIN-CERT", "FAKE-ADMIN-KEY", "FAKE-SERVER-CA-CERT"))));

  private static BootstrapIdentity identity(String cluster) {
    final String host = cluster.substring(0, cluster.indexOf('-'));
    return new BootstrapIdentity(
        cluster,
        1,
        "token",
        "cluster.local",
        "dev",
        "master",
        1,
        "workload",
        host + "-nixos",
        cluster + "-master",
        cluster + "-master." + host);
  }

  /** The unit as the vcluster domain registers it, so an unregistered unit renders nothing. */
  private static List<ManifestsUnit> registered() {
    final List<ManifestsUnit> units = new ArrayList<>();
    for (final ManifestsUnit unit : new VclusterDomainRegistrar().domain().units()) {
      if (unit.manifestUnitId().endsWith("/vcluster-certs")) {
        units.add(unit);
      }
    }
    return units;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> secrets(
      Path outdir, String cluster, Optional<VclusterPki> pki) {
    final App app = new App(AppProps.builder().outdir(outdir.toString()).build());
    final Chart chart = new Chart(app, "manifests");
    final ManifestSynthesisRequest request =
        ManifestSynthesisRequest.builder(outdir, outdir.resolve("manifests.yaml"))
            .bootstrapIdentity(identity(cluster))
            .manifestDomainPolicy(Optional.of(POLICY))
            .vclusterPki(pki)
            .build();
    try (var bound = ManifestSynthesisContext.of(request).bind()) {
      for (final ManifestsUnit unit : registered()) {
        unit.apply(
            new ManifestsUnitContext(
                chart,
                ManifestDomainCatalog.VCLUSTER,
                unit.manifestUnitId(),
                new Cdk8sApiObjectResolver(chart),
                POLICY,
                new DefaultNodeEnvContext(identity(cluster)),
                new YamlMapper()));
      }
    }
    final List<Map<String, Object>> found = new ArrayList<>();
    for (final Object document : chart.toJson()) {
      final Map<String, Object> d = (Map<String, Object>) document;
      if ("Secret".equals(d.get("kind"))) {
        found.add(d);
      }
    }
    return found;
  }

  @Test
  @SuppressWarnings("unchecked")
  void nikopol_wrkld_renders_ccr_certs_in_vcluster_ccr_with_exactly_the_roots(
      @TempDir Path outdir) {
    final List<Map<String, Object>> found = secrets(outdir, "nikopol-wrkld", Optional.of(PKI));
    assertEquals(1, found.size(), "one Secret: " + found);
    final Map<String, Object> metadata = (Map<String, Object>) found.get(0).get("metadata");
    assertEquals("ccr-certs", metadata.get("name"));
    assertEquals("vcluster-ccr", metadata.get("namespace"));
    // Flux creates it once and leaves it: vcluster renews its leaf certs in this very Secret
    // (pkg/certs/ensure.go), and an owning Flux would put the old ones back on every reconcile.
    assertEquals(
        "IfNotPresent",
        ((Map<String, Object>) metadata.get("annotations")).get("kustomize.toolkit.fluxcd.io/ssa"));
    final Map<String, String> data = (Map<String, String>) found.get(0).get("data");
    assertEquals(
        Set.of(
            "ca.crt",
            "ca.key",
            "server-ca.crt",
            "server-ca.key",
            "client-ca.crt",
            "client-ca.key",
            "front-proxy-ca.crt",
            "front-proxy-ca.key",
            "etcd-ca.crt",
            "etcd-ca.key",
            "sa.key",
            "sa.pub"),
        data.keySet());
    assertEquals(
        "FAKE-SERVER-CA-KEY", new String(Base64.getDecoder().decode(data.get("server-ca.key"))));
    assertTrue(
        data.values().stream()
            .noneMatch(v -> new String(Base64.getDecoder().decode(v)).contains("ADMIN")),
        "the operator's admin credential never rides the branch");
  }

  @Test
  void no_other_plane_renders_it_its_adopter_included(@TempDir Path outdir) {
    for (final String cluster : List.of("nikopol-mgmt", "bioskop-mgmt", "bioskop-wrkld")) {
      assertEquals(
          List.of(),
          secrets(outdir.resolve(cluster), cluster, Optional.of(PKI)),
          cluster + " rendered ccr's certs");
    }
  }

  @Test
  void without_the_material_nothing_is_rendered(@TempDir Path outdir) {
    assertEquals(List.of(), secrets(outdir, "nikopol-wrkld", Optional.empty()));
  }
}
