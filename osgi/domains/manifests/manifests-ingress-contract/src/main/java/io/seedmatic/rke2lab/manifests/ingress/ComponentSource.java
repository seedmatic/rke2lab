package io.seedmatic.rke2lab.manifests.ingress;

import java.util.Optional;

/**
 * The upstream provenance of a bumpable {@link Component}: where its newer versions are published,
 * and — for the components whose version ALSO drives a vendored upstream manifest — the release
 * asset name and the {@code manifests-core} resource dir that asset is dropped into. A pure value
 * record co-located with {@link Component} so the whole "what is this component and where does its
 * version come from" truth lives in ONE place; the {@code versions} bumper reads it to query the
 * upstream and refresh the vendored asset.
 *
 * @param upstream where newer versions are published
 * @param releaseAssetName the release asset the vendored manifest is fetched from (e.g. {@code
 *     operator-components.yaml}), empty when the component vendors no upstream manifest; a GitHub
 *     upstream only
 * @param vendoredResourceDir the {@code manifests-core} resource dir the asset is dropped into,
 *     relative to {@code src/main/resources} (e.g. {@code upstream/clusterapi/operator}), empty
 *     when none — the file itself is {@code release-<version>.yaml}
 */
public record ComponentSource(
    Upstream upstream, Optional<String> releaseAssetName, Optional<String> vendoredResourceDir) {

  /** Where a component's newer versions are published. */
  public sealed interface Upstream {

    /** The common version contract's {@code upstream} field: {@code <kind>:<locator>}. */
    String locator();

    /** GitHub releases of {@code owner/repo}. */
    record GitHub(String repo) implements Upstream {
      @Override
      public String locator() {
        return "github:" + repo;
      }
    }

    /** A Helm repository's {@code index.yaml}, the {@code chart}'s entries. */
    record HelmIndex(String repoUrl, String chart) implements Upstream {
      @Override
      public String locator() {
        return "helm:" + repoUrl + "#" + chart;
      }
    }

    /** An OCI registry's tags of {@code repository} on {@code registry} (anonymous pull). */
    record Oci(String registry, String repository) implements Upstream {
      @Override
      public String locator() {
        return "oci:" + registry + "/" + repository;
      }
    }
  }

  /** Whether this component's version drives a vendored {@code release-<version>.yaml} asset. */
  public boolean hasUpstreamYaml() {
    return releaseAssetName.isPresent() && vendoredResourceDir.isPresent();
  }
}
