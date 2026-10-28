package io.seedmatic.rke2lab.manifests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.manifests.contract.ManifestExplodeResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.felix.resolver.Logger;
import org.apache.felix.resolver.ResolverImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

/**
 * The Tekton cells' gate, planned from a tree: the cell rendering TektonConfig is health-checked on
 * it with a CEL that needs a Ready=True condition, and the cell of a Task waits on that cell.
 * kstatus alone calls a just-created TektonConfig, which has no conditions yet, current.
 */
final class FluxReadinessGateTest {

  private record StubUnit(String manifestUnitId, List<String> dependsOnManifestsUnitIds)
      implements ManifestsUnit {
    @Override
    public void apply(final ManifestsUnitContext context) {}
  }

  private static Path write(final Path target, final String cell, final String yaml)
      throws Exception {
    final Path file = target.resolve(cell).resolve("doc.yml");
    Files.createDirectories(file.getParent());
    Files.writeString(file, yaml);
    return file;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> plannedSpec(final Path target, final String name)
      throws Exception {
    final List<Path> written = new ArrayList<>();
    written.add(
        write(
            target,
            "operators/cicd/tekton-operator",
            """
            apiVersion: apiextensions.k8s.io/v1
            kind: CustomResourceDefinition
            metadata: {name: tektonconfigs.operator.tekton.dev}
            spec: {group: operator.tekton.dev, names: {kind: TektonConfig}}
            """));
    written.add(
        write(
            target,
            "workloads/cicd/tekton-pipelines",
            """
            apiVersion: operator.tekton.dev/v1alpha1
            kind: TektonConfig
            metadata: {name: config}
            """));
    written.add(
        write(
            target,
            "workloads/cicd/render-pipeline",
            """
            apiVersion: tekton.dev/v1
            kind: Task
            metadata: {name: render-publish, namespace: rke2lab-system}
            """));
    final ManifestsDomainRegistry registry =
        new ManifestsDomainRegistry(
            List.of(
                new ManifestsDomain(
                    "cicd",
                    List.of(),
                    List.of(
                        new StubUnit("cicd/tekton-operator", List.of()),
                        new StubUnit("cicd/tekton-pipelines", List.of("cicd/tekton-operator")),
                        new StubUnit("cicd/render-pipeline", List.of())))));
    new FluxServiceKustomizationPlanner(
            registry.resolve(new ResolverImpl(new Logger(Logger.LOG_ERROR))), new YamlMapper())
        .plan(new ManifestExplodeResult(target, written));
    final Path planned = target.resolve("flux").resolve("cicd").resolve(name + ".yml");
    return (Map<String, Object>)
        ((Map<String, Object>) new Yaml().load(Files.readString(planned))).get("spec");
  }

  @Test
  @SuppressWarnings("unchecked")
  void theTektonConfigCellIsCurrentOnlyOnAReadyTrueCondition(@TempDir final Path target)
      throws Exception {
    final Map<String, Object> spec = plannedSpec(target, "cicd-tekton-pipelines");
    assertEquals(false, spec.get("wait"));
    assertEquals(
        List.of(
            Map.of(
                "apiVersion",
                "operator.tekton.dev/v1alpha1",
                "kind",
                "TektonConfig",
                "name",
                "config")),
        spec.get("healthChecks"));
    final List<Map<String, Object>> exprs =
        (List<Map<String, Object>>) spec.get("healthCheckExprs");
    assertEquals(1, exprs.size(), String.valueOf(exprs));
    assertEquals("TektonConfig", exprs.get(0).get("kind"));
    final String current = String.valueOf(exprs.get(0).get("current"));
    assertTrue(current.contains("status.conditions.exists("), current);
    assertTrue(current.contains("c.type == 'Ready' && c.status == 'True'"), current);
  }

  @Test
  @SuppressWarnings("unchecked")
  void aTaskCellWaitsOnTheTektonConfigCell(@TempDir final Path target) throws Exception {
    final List<Map<String, Object>> dependsOn =
        (List<Map<String, Object>>) plannedSpec(target, "cicd-render-pipeline").get("dependsOn");
    assertTrue(
        dependsOn.contains(Map.of("name", "cicd-tekton-pipelines")), String.valueOf(dependsOn));
  }
}
