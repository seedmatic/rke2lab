package io.seedmatic.rke2lab.manifests.units.clusterapi;

import io.seedmatic.rke2lab.manifests.contract.profiles.ImageState;
import io.seedmatic.rke2lab.manifests.contract.profiles.NodeRuntime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The typed model of our {@code NodeImage} CR's {@code spec} — the first of our own CRs rendered
 * from a RECORD rather than a hand-rolled {@code Map.of}.
 *
 * <p>★ Why a type and not a map. Every other CR here is built by naming string keys inline, so a
 * renamed or forgotten field is caught at the earliest by the API server rejecting the object at
 * Flux apply time, and at worst by a controller reading a zero value. A record makes the field set
 * the compiler's business on the producing side, and gives the shape one readable definition.
 *
 * <p>⚠️ What it does NOT do: prove agreement with the deployed CRD schema. The component names here
 * still match the Go struct tags by CONVENTION. Proving it needs POJOs generated from the CRD — the
 * graved target (fabric8 {@code java-generator} over the CRDs this build already stages into {@code
 * target/generated-resources/crds}). This is the structured PRODUCER, not the generated contract.
 *
 * <p>The nesting is the CR's, not {@link ImageState}'s: the flat {@code incusProject} / {@code
 * incusRemoteAddress} pair becomes {@code source}, and the four security booleans become {@code
 * security}, because each group maps onto one thing — where the artifact lives, and the incus
 * {@code security.*} key family. {@link #toSpec()} flattens to maps only at the cdk8s edge
 * (structured inside, flat at the boundary).
 */
record NodeImageCr(
    String alias,
    String fingerprint,
    String buildChecksum,
    String rke2Version,
    Source source,
    Runtime runtime) {

  /** Where the artifact lives — the incus engine holding it and the project within it. */
  record Source(String endpoint, String project) {}

  /** The container contract an instance must carry to run this image. */
  record Runtime(List<String> kernelModules, String rawLxc, Security security) {}

  /** The incus {@code security.*} key family. */
  record Security(
      boolean privileged, boolean nesting, boolean interceptBpf, boolean interceptBpfDevices) {}

  /**
   * Project the realised {@link ImageState} onto the CR's shape. Total — every component of the CR
   * is fed from the amendment, so no field is left to a default.
   */
  static NodeImageCr of(final ImageState image) {
    final NodeRuntime contract = image.runtime();
    return new NodeImageCr(
        image.imageAlias(),
        image.imageFingerprint(),
        image.imageBuildChecksum(),
        image.rke2Version(),
        new Source(image.incusRemoteAddress(), image.incusProject()),
        new Runtime(
            contract.kernelModules(),
            contract.rawLxc(),
            new Security(
                contract.privileged(),
                contract.nesting(),
                contract.interceptBpf(),
                contract.interceptBpfDevices())));
  }

  /**
   * The {@code /spec} body for the cdk8s JSON patch — the flatten-at-edge boundary. {@link
   * LinkedHashMap} rather than {@code Map.of} so the rendered YAML keeps a stable field order and a
   * diff of the manifests branch reads as a change, not a reshuffle.
   */
  Map<String, Object> toSpec() {
    final Map<String, Object> spec = new LinkedHashMap<>();
    spec.put("alias", alias);
    spec.put("fingerprint", fingerprint);
    spec.put("buildChecksum", buildChecksum);
    spec.put("rke2Version", rke2Version);
    final Map<String, Object> sourceSpec = new LinkedHashMap<>();
    sourceSpec.put("endpoint", source.endpoint());
    sourceSpec.put("project", source.project());
    spec.put("source", sourceSpec);
    final Map<String, Object> securitySpec = new LinkedHashMap<>();
    securitySpec.put("privileged", runtime.security().privileged());
    securitySpec.put("nesting", runtime.security().nesting());
    securitySpec.put("interceptBPF", runtime.security().interceptBpf());
    securitySpec.put("interceptBPFDevices", runtime.security().interceptBpfDevices());
    final Map<String, Object> runtimeSpec = new LinkedHashMap<>();
    runtimeSpec.put("kernelModules", runtime.kernelModules());
    runtimeSpec.put("rawLXC", runtime.rawLxc());
    runtimeSpec.put("security", securitySpec);
    spec.put("runtime", runtimeSpec);
    return spec;
  }
}
