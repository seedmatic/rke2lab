package io.seedmatic.rke2lab.manifests.contract;

import java.nio.file.Path;
import java.util.Objects;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * Result contract for canonical manifest synthesis.
 *
 * @param manifestFile consolidated K8s manifest file (YAML)
 * @param manifestUnitHitCount number of manifest units processed
 * @param domainCount number of domains synthesized
 */
public record ManifestSynthesisResult(
    Path manifestFile, int manifestUnitHitCount, int domainCount) {

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    @MonotonicNonNull private Path manifestFile;
    private int manifestUnitHitCount;
    private int domainCount;

    private Builder() {}

    public Builder manifestFile(Path value) {
      this.manifestFile = value;
      return this;
    }

    public Builder manifestUnitHitCount(int value) {
      this.manifestUnitHitCount = value;
      return this;
    }

    public Builder domainCount(int value) {
      this.domainCount = value;
      return this;
    }

    public ManifestSynthesisResult build() {
      return new ManifestSynthesisResult(
          Objects.requireNonNull(manifestFile, "manifestFile"), manifestUnitHitCount, domainCount);
    }
  }
}
