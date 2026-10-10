package io.seedmatic.rke2lab.manifests.units.clusterapi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.seedmatic.rke2lab.manifests.Cdk8sApiObjectResolver;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.YamlMapper;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainPolicy;
import io.seedmatic.rke2lab.manifests.contract.ManifestSynthesisRequest;
import io.seedmatic.rke2lab.manifests.contract.profiles.ClusterIssuerCaMaterial;
import io.seedmatic.rke2lab.manifests.contract.profiles.GithubAppMaterial;
import io.seedmatic.rke2lab.manifests.contract.profiles.ManagementClusterCaMaterial;
import io.seedmatic.rke2lab.manifests.contract.profiles.OperatorPkiMaterial;
import io.seedmatic.rke2lab.manifests.contract.profiles.WorkloadClusterCasMaterial;
import io.seedmatic.rke2lab.manifests.node.DefaultNodeEnvContext;
import io.seedmatic.rke2lab.manifests.units.cicd.PacSecretManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.gitops.GithubAppSecretManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.platform.ClusterIssuerManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.platform.GithubTokenManagerManifestsUnit;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.cdk8s.App;
import org.cdk8s.AppProps;
import org.cdk8s.Chart;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * Golden renders of the units that EMBED sealed material — the App's credentials, the operator's
 * PKI, the cluster CAs. A survey renders none of it (there is no cellar, so every reveal is empty
 * and those units emit only their group marker), which is why these units had no render test that
 * could see a change to what they publish. Here every material is present, so the Secrets,
 * kubeconfigs and CA bundles are rendered, and the bytes are pinned.
 *
 * <p>The goldens were produced by the code as it stood BEFORE the units' material types changed
 * (commit {@code 5m-0}, on the manifests mirrors), so a later change of those types is checked
 * against bytes it did not write. A change here is a change of what a cluster receives: it is a
 * finding, not a golden to refresh.
 *
 * <p>Two limits, said rather than implied. The goldens pin the bytes for THESE fixtures, not for
 * every material. And every material is an obvious placeholder: rke2lab is public, and a
 * structurally valid key is still a leaked key to a secret scanner. None of these units parses what
 * it embeds — they copy or base64 it — so a placeholder exercises exactly the code a real key
 * would. The only fields parsed are the App's two ids, read as integers by the token-manager unit,
 * hence the two small numbers.
 *
 * <p>The actual render is also written under {@code target}, so a reviewed regeneration is a copy
 * of a file the test already produced rather than a hand edit of a golden.
 */
@Tag("osgi")
class SealedMaterialUnitsGoldenTest {

  private static final ManifestDomainCatalog CATALOG =
      ManifestDomainCatalog.builder().addDefaultDomains().build();

  private static final ManifestDomainPolicy POLICY =
      ManifestDomainPolicy.builder()
          .clusterApi(true)
          .runtime(true)
          .cluster(true)
          .gitops(true)
          .cicd(true)
          .platform(true)
          .build();

  private static WorkloadClusterCasMaterial.Pair pair(String name) {
    return new WorkloadClusterCasMaterial.Pair(
        "FAKE-" + name + "-CA-CERT-CHAIN", "FAKE-" + name + "-CA-KEY");
  }

  private static WorkloadClusterCasMaterial.Entry workload(String cluster, String tag) {
    return new WorkloadClusterCasMaterial.Entry(
        cluster,
        pair(tag + "-SERVER"),
        pair(tag + "-CLIENT"),
        pair(tag + "-ETCD-SERVER"),
        pair(tag + "-ETCD-PEER"));
  }

  /** The units that embed sealed material, grouped as they must render together. */
  enum Rendering {
    GITHUB_APP_SECRET(CATALOG.gitops()) {
      @Override
      List<ManifestsUnit> units() {
        return List.of(new GithubAppSecretManifestsUnit());
      }
    },
    PAC_SECRET(CATALOG.cicd()) {
      @Override
      List<ManifestsUnit> units() {
        return List.of(new PacSecretManifestsUnit());
      }
    },
    GITHUB_TOKEN_MANAGER(CATALOG.platform()) {
      @Override
      List<ManifestsUnit> units() {
        return List.of(new GithubTokenManagerManifestsUnit());
      }
    },
    CLUSTER_ISSUER(CATALOG.platform()) {
      @Override
      List<ManifestsUnit> units() {
        return List.of(new ClusterIssuerManifestsUnit());
      }
    },
    CLUSTER_KUBECONFIG(CATALOG.clusterApi()) {
      @Override
      List<ManifestsUnit> units() {
        return List.of(new ClusterKubeconfigManifestsUnit());
      }
    },
    // The management and workload units share the CR renderer and render into one chart, the way a
    // synthesis applies them — rendered apart, the workload unit would miss what management emits.
    CLUSTER_API(CATALOG.clusterApi()) {
      @Override
      List<ManifestsUnit> units() {
        final ClusterApiCrRenderer renderer = new ClusterApiCrRenderer();
        return List.of(
            new ClusterApiManagementManifestsUnit(renderer),
            new ClusterApiWorkloadManifestsUnit(renderer));
      }
    };

    private final String domain;

    Rendering(String domain) {
      this.domain = domain;
    }

    abstract List<ManifestsUnit> units();

    String golden() {
      return "golden/sealed-material/" + name().toLowerCase().replace('_', '-') + ".json";
    }
  }

  private final SeedCodec codec = new SeedCodec();

  @TestFactory
  Stream<DynamicTest> a_unit_embedding_sealed_material_renders_its_golden_bytes(
      @TempDir Path outdir) {
    return Stream.of(Rendering.values())
        .map(
            rendering ->
                DynamicTest.dynamicTest(
                    rendering.name(),
                    () -> {
                      final String actual =
                          codec
                              .canonical()
                              .writeJson(render(rendering, outdir.resolve(rendering.name())));
                      keep(rendering, actual);
                      assertEquals(
                          golden(rendering),
                          actual,
                          "the rendered bytes of " + rendering + " changed");
                    }));
  }

  private List<Object> render(Rendering rendering, Path outdir) {
    final App app = new App(AppProps.builder().outdir(outdir.toString()).build());
    final Chart chart = new Chart(app, "manifests");
    final ManifestSynthesisRequest request =
        ManifestSynthesisRequest.builder(outdir, outdir.resolve("manifests.yaml"))
            .bootstrapIdentity(ClusterApiRenderTest.identity())
            .imageState(Optional.of(ClusterApiRenderTest.imageState()))
            .clusterFleet(Optional.of(ClusterApiRenderTest.FLEET))
            .manifestDomainPolicy(Optional.of(POLICY))
            .fabricBridgeParent(Optional.of(ClusterApiRenderTest.FABRIC_BRIDGE))
            .githubApp(
                Optional.of(new GithubAppMaterial("1001", "2002", "FAKE-GITHUB-APP-PRIVATE-KEY")))
            .operatorPki(
                Optional.of(
                    new OperatorPkiMaterial(
                        "FAKE-OPERATOR-CLIENT-CERT",
                        "FAKE-OPERATOR-CLIENT-KEY",
                        "FAKE-OPERATOR-CA-CERT")))
            .clusterIssuerCa(
                Optional.of(
                    new ClusterIssuerCaMaterial(
                        "FAKE-CLUSTER-ISSUER-CA-CERT-CHAIN", "FAKE-CLUSTER-ISSUER-CA-KEY")))
            .managementCas(
                Optional.of(
                    new ManagementClusterCaMaterial(
                        pair("MGMT-SERVER"),
                        pair("MGMT-CLIENT"),
                        pair("MGMT-ETCD-SERVER"),
                        pair("MGMT-ETCD-PEER"))))
            .workloadCas(
                Optional.of(
                    new WorkloadClusterCasMaterial(
                        List.of(
                            workload("bioskop-wrkld", "BIOSKOP-WRKLD"),
                            workload("nikopol-mgmt", "NIKOPOL-MGMT")))))
            .build();

    try (var bound = ManifestSynthesisContext.of(request).bind()) {
      for (final ManifestsUnit unit : rendering.units()) {
        unit.apply(
            new ManifestsUnitContext(
                chart,
                rendering.domain,
                unit.manifestUnitId(),
                new Cdk8sApiObjectResolver(chart),
                POLICY,
                new DefaultNodeEnvContext(ClusterApiRenderTest.identity()),
                new YamlMapper()));
      }
    }
    return new ArrayList<>(chart.toJson());
  }

  private Path buildDirectory() {
    try {
      return Path.of(
              SealedMaterialUnitsGoldenTest.class
                  .getProtectionDomain()
                  .getCodeSource()
                  .getLocation()
                  .toURI())
          .getParent();
    } catch (java.net.URISyntaxException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private String golden(Rendering rendering) {
    try (InputStream in =
        SealedMaterialUnitsGoldenTest.class
            .getClassLoader()
            .getResourceAsStream(rendering.golden())) {
      if (in == null) {
        throw new IllegalStateException(
            "no golden at " + rendering.golden() + " — the actual render is kept under target");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  /**
   * Beside the compiled tests, so under whichever build directory the profile chose ({@code target}
   * or {@code target~claude}), never in the source tree.
   */
  private void keep(Rendering rendering, String actual) {
    final Path kept = buildDirectory().resolve("golden-actual").resolve(rendering.golden());
    try {
      Files.createDirectories(kept.getParent());
      Files.writeString(kept, actual);
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }
}
