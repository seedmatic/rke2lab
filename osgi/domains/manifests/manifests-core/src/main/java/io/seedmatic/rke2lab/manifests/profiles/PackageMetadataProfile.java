// @codebase
package io.seedmatic.rke2lab.manifests.profiles;

import io.seedmatic.rke2lab.manifests.contract.ManifestAnnotation;
import io.seedmatic.rke2lab.manifests.contract.ManifestLayer;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PackageMetadataProfile {

  private final String domain;
  private final String packageName;
  // When set, every resource this profile stamps carries the NODE_BOOTSTRAP lane annotation, so the
  // exploder routes it into the node-side bootstrap file instead of the committed branch tree.
  private final boolean nodeBootstrap;
  // The reconcile layer this unit's resources default to; WORKLOADS when unset. A per-resource call
  // may override it via an extraAnnotations MANIFEST_LAYER entry.
  private final ManifestLayer defaultLayer;

  public PackageMetadataProfile(final String domain, final String packageName) {
    this(domain, packageName, false);
  }

  public PackageMetadataProfile(
      final String domain, final String packageName, final boolean nodeBootstrap) {
    this(domain, packageName, nodeBootstrap, ManifestLayer.WORKLOADS);
  }

  public PackageMetadataProfile(
      final String domain,
      final String packageName,
      final boolean nodeBootstrap,
      final ManifestLayer defaultLayer) {
    this.domain = domain;
    this.packageName = packageName;
    this.nodeBootstrap = nodeBootstrap;
    this.defaultLayer = defaultLayer;
  }

  // Stamp the base DOMAIN + PACKAGE markers, then merge the lane + layer markers into whatever the
  // caller passed — a single instance seam so no call site can forget them. A per-resource
  // MANIFEST_LAYER in extraAnnotations always wins; otherwise the unit's non-default layer is
  // stamped (workloads stays implicit — absent means workloads).
  private Map<String, String> stamp(final Map<String, String> extraAnnotations) {
    final LinkedHashMap<String, String> annotations = new LinkedHashMap<>();
    annotations.put(ManifestAnnotation.DOMAIN.key(), domain);
    annotations.put(ManifestAnnotation.PACKAGE.key(), packageName);
    annotations.putAll(extraAnnotations);
    if (nodeBootstrap) {
      annotations.put(ManifestAnnotation.NODE_BOOTSTRAP.key(), "true");
    }
    if (!annotations.containsKey(ManifestAnnotation.MANIFEST_LAYER.key())
        && defaultLayer != ManifestLayer.WORKLOADS) {
      annotations.put(ManifestAnnotation.MANIFEST_LAYER.key(), defaultLayer.value());
    }
    return Map.copyOf(annotations);
  }

  public Map<String, String> packageAnnotations() {
    return stamp(Map.of());
  }

  public Map<String, String> packageAnnotations(final Map<String, String> extraAnnotations) {
    return stamp(extraAnnotations);
  }

  public Map<String, String> templateAnnotations(final Map<String, String> extraAnnotations) {
    return stamp(extraAnnotations);
  }
}
