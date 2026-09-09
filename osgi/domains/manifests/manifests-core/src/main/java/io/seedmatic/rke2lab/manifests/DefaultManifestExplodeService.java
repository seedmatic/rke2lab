// @codebase
package io.seedmatic.rke2lab.manifests;

import com.fasterxml.jackson.databind.JsonNode;
import io.seedmatic.rke2lab.manifests.contract.ManifestAnnotation;
import io.seedmatic.rke2lab.manifests.contract.ManifestExplodeRequest;
import io.seedmatic.rke2lab.manifests.contract.ManifestExplodeResult;
import io.seedmatic.rke2lab.manifests.contract.ManifestExplodeService;
import io.seedmatic.rke2lab.manifests.contract.ManifestLayer;
import io.seedmatic.rke2lab.manifests.contract.NodeBootstrapArtifact;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Splits a consolidated multi-document YAML synth into one file per resource under {@code
 * <domain>/<package>/<order>-<kind>-<name>.yml}.
 *
 * <p>Replaces the old {@code bin/explode-manifests.sh} that used {@code yq} — the synth itself runs
 * in seed-master at pulumi-up time now, and we don't want a {@code yq} runtime dep.
 *
 * <p>Domain and package come from {@code io.seedmatic.rke2lab/domain} and {@code
 * io.seedmatic.rke2lab/package} annotations stamped by domain code; defaults match the old script
 * ({@code default} / {@code unknown}).
 *
 * <p>The per-resource file name is annotation-driven (see {@link #fileNameFor}): {@link
 * ManifestAnnotation#RKE2_CONFIG} resources are written verbatim ({@code <name>}) so {@code
 * rke2lab-config-install.sh} can glob them; {@link ManifestAnnotation#MANIFEST_GROUP} and {@link
 * ManifestAnnotation#LOCAL_CONFIG} resources become hidden dotfiles ({@code .<kind>-<name>.yml})
 * that are never linked or globbed; everything else gets an {@code <order>-<kind>-<name>.yml} name
 * for cluster apply, where order is {@code 00-} for CRDs, {@code 01-} for other cluster-scoped
 * resources (no namespace), {@code 02-} for namespace-scoped resources.
 */
@Component(service = ManifestExplodeService.class)
public final class DefaultManifestExplodeService implements ManifestExplodeService {

  private static final Logger LOG = LoggerFactory.getLogger(DefaultManifestExplodeService.class);

  private static final String CRD_KIND = "CustomResourceDefinition";

  private final YamlMapper yaml;

  @Activate
  public DefaultManifestExplodeService(@Reference YamlMapper yaml) {
    this.yaml = yaml;
  }

  @Override
  public String providerId() {
    return "default-jackson-exploder";
  }

  @Override
  public ManifestExplodeResult explode(ManifestExplodeRequest request) throws IOException {
    final Path source = request.consolidatedManifestFile();
    final Path target = request.explodedTargetDir();

    if (!Files.isRegularFile(source)) {
      throw new IllegalStateException("Consolidated manifest file not found: " + source);
    }

    // Note: we do NOT wipe target here. The caller may have other content under
    // it (e.g. host/ assets in seed-master's manifestsRoot) that must survive.
    // Callers are responsible for clearing stale per-resource files before
    // invoking explode.
    Files.createDirectories(target);
    writeSopsGuard(target);

    final List<Path> written = new ArrayList<>();
    // The node-side bootstrap lane: resources marked NODE_BOOTSTRAP are NOT written into the
    // per-resource branch tree (they are never committed nor applied from the rendered branch).
    // They are collected here and emitted as a single multi-doc file OUTSIDE that tree, for the
    // host
    // to seed onto the node over devlxd (see nixos/bootstrap-manifests.nix).
    final List<JsonNode> bootstrapDocuments = new ArrayList<>();

    yaml.read(source)
        .nodes()
        .filter(JsonNode::isObject)
        .forEach(
            document -> {
              final Optional<String> kind = text(document.get("kind"));
              if (kind.isEmpty()) {
                return;
              }
              if (isAnnotated(document, ManifestAnnotation.NODE_BOOTSTRAP.key())) {
                bootstrapDocuments.add(document);
                return;
              }
              final String domain =
                  annotation(document, ManifestAnnotation.DOMAIN.key(), "default");
              final String pkg = annotation(document, ManifestAnnotation.PACKAGE.key(), "unknown");
              final Optional<String> namespace = text(document.path("metadata").get("namespace"));
              final String name = sanitizeFileSegment(text(document.path("metadata").get("name")));

              final String fileName = fileNameFor(document, kind.get(), namespace, name);
              final String layer = layerFor(document, kind.get());
              final Path outFile =
                  target.resolve(layer).resolve(domain).resolve(pkg).resolve(fileName);
              yaml.write(outFile).document(document);
              written.add(outFile);
            });

    written.sort(Comparator.naturalOrder());

    LOG.info("Exploded {} resources from {} into {}", written.size(), source.getFileName(), target);

    if (!bootstrapDocuments.isEmpty()) {
      // Collected in the consolidated file's document order, which follows the canonical
      // manifest-unit visit order (deterministic + dependency-respecting — see ManifestsVisitOrder,
      // Kahn with a sorted tie-break). So this concatenated file's bytes are stable run to run (no
      // instance-config churn) AND apply in dependency order (e.g. the flux-system Namespace before
      // the FluxInstance that lands in it). No re-sort here would only break that dependency order.
      final Path bootstrapFile = NodeBootstrapArtifact.MANIFESTS.in(target);
      Files.createDirectories(bootstrapFile.getParent());
      yaml.write(bootstrapFile).documents(bootstrapDocuments);
      LOG.info(
          "Collected {} node-bootstrap resources into {}",
          bootstrapDocuments.size(),
          bootstrapFile);
    }

    return new ManifestExplodeResult(target, written);
  }

  /**
   * Belt-and-suspenders: bind any Secret rendered into the branch tree to the sops clean filter, so
   * if one ever carries real data its {@code data}/{@code stringData} commits ENCRYPTED (the age
   * recipients in the repo's {@code .sops.yaml}), never plaintext. The PRIMARY guarantee stays that
   * real-data secrets ride the {@link ManifestAnnotation#NODE_BOOTSTRAP} lane and never reach the
   * branch at all; this {@code .gitattributes} is the second belt, engaged by any filter-aware
   * committer over the tree (the operator's git CLI, which carries the {@code sops-yaml} filter).
   */
  private void writeSopsGuard(final Path target) throws IOException {
    Files.writeString(
        target.resolve(".gitattributes"),
        """
        # Rendered Secrets are sops-filtered: their data/stringData commit ENCRYPTED (never
        # plaintext), decrypted by Flux (sops-age) on reconcile. The recorded .sops.yaml names the
        # fields + recipients; only the irreducible bootstrap seed still rides the node-bootstrap lane.
        **/*-secret-*.yml filter=sops-yaml
        **/.secret-*.yml filter=sops-yaml
        """);
  }

  /**
   * Resolves the per-resource file name from annotations, not from the resource name:
   *
   * <ul>
   *   <li>{@link ManifestAnnotation#RKE2_CONFIG}: verbatim {@code <name>} (visible) so {@code
   *       rke2lab-config-install.sh} can glob it into {@code config.yaml.d}.
   *   <li>{@link ManifestAnnotation#MANIFEST_GROUP} or {@link ManifestAnnotation#LOCAL_CONFIG}:
   *       hidden {@code .<kind>-<name>.yml} dotfile, never linked / globbed.
   *   <li>otherwise: {@code <order>-<kind>-<name>.yml} for cluster apply.
   * </ul>
   */
  private String fileNameFor(
      JsonNode document, String kind, Optional<String> namespace, String name) {
    if (isAnnotated(document, ManifestAnnotation.RKE2_CONFIG.key())) {
      return name;
    }
    if (isAnnotated(document, ManifestAnnotation.MANIFEST_GROUP.key())
        || isAnnotated(document, ManifestAnnotation.LOCAL_CONFIG.key())) {
      return "." + kind.toLowerCase(Locale.ROOT) + "-" + name + ".yml";
    }
    final String order = orderPrefixFor(kind, namespace);
    return order + "-" + kind.toLowerCase(Locale.ROOT) + "-" + name + ".yml";
  }

  private boolean isAnnotated(JsonNode document, String key) {
    return "true".equalsIgnoreCase(annotation(document, key, "false"));
  }

  /**
   * The reconcile layer sub-dir a resource explodes into: a {@code CustomResourceDefinition} is
   * forced to {@code crds} by kind (so it applies before any CR, regardless of its unit's
   * annotation); everything else takes its {@link ManifestAnnotation#MANIFEST_LAYER} annotation,
   * defaulting to {@code workloads}. {@code FluxRootManifestsUnit} emits one {@code Kustomization}
   * per layer, chained by {@code dependsOn}.
   */
  private String layerFor(JsonNode document, String kind) {
    if (CRD_KIND.equals(kind)) {
      return ManifestLayer.CRDS.value();
    }
    return annotation(
        document, ManifestAnnotation.MANIFEST_LAYER.key(), ManifestLayer.WORKLOADS.value());
  }

  private String orderPrefixFor(String kind, Optional<String> namespace) {
    if (CRD_KIND.equals(kind)) {
      return "00";
    }
    return namespace.filter(ns -> !ns.isBlank()).isPresent() ? "02" : "01";
  }

  private String annotation(JsonNode document, String key, String fallback) {
    final JsonNode value = document.path("metadata").path("annotations").get(key);
    return value == null || value.isNull() ? fallback : value.asText();
  }

  private Optional<String> text(JsonNode node) {
    return Optional.ofNullable(node).filter(n -> !n.isNull()).map(JsonNode::asText);
  }

  private String sanitizeFileSegment(Optional<String> value) {
    return value
        .filter(v -> !v.isBlank())
        .map(v -> v.toLowerCase(Locale.ROOT).replace(':', '-').replace('/', '-'))
        .orElse("unnamed");
  }
}
