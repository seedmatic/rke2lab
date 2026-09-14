package io.seedmatic.rke2lab.manifests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.seedmatic.rke2lab.manifests.contract.ManifestAnnotation;
import io.seedmatic.rke2lab.manifests.contract.ManifestExplodeRequest;
import io.seedmatic.rke2lab.manifests.contract.ManifestExplodeResult;
import io.seedmatic.rke2lab.manifests.contract.ManifestExplodeService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;

/**
 * Locks the annotation-driven file naming in the {@link ManifestExplodeService} @Component, run
 * IN-CONTAINER: the per-resource file name is decided by annotations, never by the resource name.
 * Regression guard for the empty {@code config.yaml.d} bug, where RKE2 config fragments were
 * dotfiled into {@code .configmap-<name>.yaml.yml} and missed the installer's {@code *.yaml}/{@code
 * *.yml} glob.
 *
 * <p>The service is acquired from the registry through the bundle's own {@link BundleContext} — SCR
 * activated {@code DefaultManifestExplodeService} with its {@code @Reference} to the {@code
 * YamlMapper} @Component satisfied — never {@code new DefaultManifestExplodeService(new
 * YamlMapper())}. That hand-wiring was the pre-OSGI debt this migration pays down: the fixture is
 * now what DS injects, proving the @Component graph activates in-container (the cdk8s carrier + its
 * systemd fragment included).
 */
class DefaultManifestExplodeServiceTest {

  private static ManifestExplodeService explodeService() {
    final BundleContext context =
        FrameworkUtil.getBundle(DefaultManifestExplodeServiceTest.class).getBundleContext();
    final var reference = context.getServiceReference(ManifestExplodeService.class);
    assertNotNull(
        reference,
        "SCR must publish ManifestExplodeService — DefaultManifestExplodeService activated with its"
            + " @Reference to the YamlMapper @Component satisfied.");
    final ManifestExplodeService service = context.getService(reference);
    assertNotNull(service, "the ManifestExplodeService reference must resolve to an instance");
    return service;
  }

  private static YamlMapper yamlMapper() {
    final BundleContext context =
        FrameworkUtil.getBundle(DefaultManifestExplodeServiceTest.class).getBundleContext();
    final var reference = context.getServiceReference(YamlMapper.class);
    assertNotNull(reference, "SCR must publish the YamlMapper @Component.");
    return context.getService(reference);
  }

  @Test
  void rke2ConfigConfigMapTakesVisibleName(@TempDir Path tmp) throws IOException {
    // An RKE2_CONFIG ConfigMap takes the normal visible name so Flux APPLIES it (a real ConfigMap
    // seed-incluster reads to bootstrap-inject a managed cluster); install-rke2-config keys on the
    // annotation, not the filename, so the branch fetch still finds it. namespaced → 02 prefix.
    final Map<String, Object> document =
        resource(
            "ConfigMap",
            "core.yaml",
            "rke2lab-bioskop-wrkld",
            "runtime",
            "rke2-config/bioskop-wrkld/control-node",
            Map.of(ManifestAnnotation.RKE2_CONFIG.key(), "true"));

    assertEquals(
        "workloads/runtime/rke2-config/bioskop-wrkld/control-node/02-configmap-core.yaml.yml",
        explodeOne(tmp, document));
  }

  @Test
  void groupMarkerBecomesHiddenDotfile(@TempDir Path tmp) throws IOException {
    final Map<String, Object> document =
        resource(
            "ConfigMap",
            "rke2-config.group",
            null,
            "runtime",
            "rke2-config",
            Map.of(
                ManifestAnnotation.LOCAL_CONFIG.key(), "true",
                ManifestAnnotation.MANIFEST_GROUP.key(), "true"));

    assertEquals(
        "workloads/runtime/rke2-config/.configmap-rke2-config.group.yml",
        explodeOne(tmp, document));
  }

  @Test
  void localConfigBecomesHiddenDotfile(@TempDir Path tmp) throws IOException {
    final Map<String, Object> document =
        resource(
            "ConfigMap",
            "cloud-config",
            null,
            "runtime",
            "cloud-config",
            Map.of(ManifestAnnotation.LOCAL_CONFIG.key(), "true"));

    assertEquals(
        "workloads/runtime/cloud-config/.configmap-cloud-config.yml", explodeOne(tmp, document));
  }

  @Test
  void namespaceScopedResourceGetsOrder02(@TempDir Path tmp) throws IOException {
    final Map<String, Object> document =
        resource("ConfigMap", "headscale-config", "mesh-system", "mesh", "headscale", Map.of());

    assertEquals(
        "workloads/mesh/headscale/02-configmap-headscale-config.yml", explodeOne(tmp, document));
  }

  @Test
  void clusterScopedResourceGetsOrder01(@TempDir Path tmp) throws IOException {
    final Map<String, Object> document =
        resource(
            "Namespace", "rke2lab-system", null, "cluster", "runtime-system-namespace", Map.of());

    assertEquals(
        "workloads/cluster/runtime-system-namespace/01-namespace-rke2lab-system.yml",
        explodeOne(tmp, document));
  }

  @Test
  void crdGetsOrder00(@TempDir Path tmp) throws IOException {
    final Map<String, Object> document =
        resource(
            "CustomResourceDefinition",
            "foos.example.com",
            null,
            "cluster-api",
            "operator",
            Map.of());

    assertEquals(
        "crds/cluster-api/operator/00-customresourcedefinition-foos.example.com.yml",
        explodeOne(tmp, document));
  }

  private static String explodeOne(final Path tmp, final Map<String, Object> document)
      throws IOException {
    final Path consolidated = tmp.resolve("synth.yaml");
    final Path target = tmp.resolve("exploded");
    yamlMapper().write(consolidated).documents(List.of(document));

    final ManifestExplodeResult result =
        explodeService().explode(new ManifestExplodeRequest(consolidated, target));

    assertEquals(1, result.writtenFileCount());
    return target.relativize(result.writtenFiles().get(0)).toString();
  }

  private static Map<String, Object> resource(
      final String kind,
      final String name,
      final String namespace,
      final String domain,
      final String pkg,
      final Map<String, String> extraAnnotations) {
    final LinkedHashMap<String, String> annotations = new LinkedHashMap<>();
    annotations.put(ManifestAnnotation.DOMAIN.key(), domain);
    annotations.put(ManifestAnnotation.PACKAGE.key(), pkg);
    annotations.putAll(extraAnnotations);

    final LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("name", name);
    if (namespace != null) {
      metadata.put("namespace", namespace);
    }
    metadata.put("annotations", annotations);

    final LinkedHashMap<String, Object> document = new LinkedHashMap<>();
    document.put("apiVersion", "v1");
    document.put("kind", kind);
    document.put("metadata", metadata);
    return document;
  }
}
