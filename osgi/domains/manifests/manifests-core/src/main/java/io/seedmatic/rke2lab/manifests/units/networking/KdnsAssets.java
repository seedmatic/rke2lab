// @codebase
package io.seedmatic.rke2lab.manifests.units.networking;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

public final class KdnsAssets {

  private static final String RESOURCE_ROOT = "/runtime/networking/kdns";

  private final Class<?> resourceAnchor;
  private final List<ConfigMapAsset> dlvScriptAssets;

  private KdnsAssets(Builder builder) {
    this.resourceAnchor = builder.resourceAnchor;
    this.dlvScriptAssets = List.copyOf(builder.dlvScriptAssets);
  }

  public static Builder builder() {
    return new Builder();
  }

  public Map<String, String> dlvScriptConfigMapData() {
    final LinkedHashMap<String, String> data = new LinkedHashMap<>();
    for (ConfigMapAsset asset : dlvScriptAssets) {
      data.put(asset.configMapKey(), readResource(asset.classpathResource()));
    }
    return Map.copyOf(data);
  }

  private String readResource(String resourcePath) {
    try (InputStream input = resourceAnchor.getResourceAsStream(resourcePath)) {
      if (input == null) {
        throw new IllegalStateException("Missing kdns resource: " + resourcePath);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed reading kdns resource: " + resourcePath, ex);
    }
  }

  public static final class Builder {
    private Class<?> resourceAnchor = KdnsAssets.class;
    private final List<ConfigMapAsset> dlvScriptAssets = new ArrayList<>();

    private Builder() {
      addDefaultDlvScriptAssets();
    }

    public Builder resourceAnchor(Class<?> resourceAnchor) {
      this.resourceAnchor = Objects.requireNonNull(resourceAnchor, "resourceAnchor");
      return this;
    }

    public Builder addDefaultDlvScriptAssets() {
      addDlvScriptAsset("kdns-dlv.sh", RESOURCE_ROOT + "/kdns-dlv.sh");
      return this;
    }

    public Builder addDlvScriptAsset(String configMapKey, String classpathResource) {
      return addDlvScriptAsset(
          ConfigMapAsset.builder()
              .configMapKey(configMapKey)
              .classpathResource(classpathResource)
              .build());
    }

    public Builder addDlvScriptAsset(Consumer<ConfigMapAsset.Builder> configMapAssetBuilder) {
      Objects.requireNonNull(configMapAssetBuilder, "configMapAssetBuilder");
      ConfigMapAsset.Builder builder = ConfigMapAsset.builder();
      configMapAssetBuilder.accept(builder);
      return addDlvScriptAsset(builder.build());
    }

    public Builder addDlvScriptAsset(ConfigMapAsset configMapAsset) {
      dlvScriptAssets.add(Objects.requireNonNull(configMapAsset, "configMapAsset"));
      return this;
    }

    public Builder clearDlvScriptAssets() {
      dlvScriptAssets.clear();
      return this;
    }

    public KdnsAssets build() {
      return new KdnsAssets(this);
    }
  }

  public static final class ConfigMapAsset {
    private final String configMapKey;
    private final String classpathResource;

    private ConfigMapAsset(Builder builder) {
      this.configMapKey = Objects.requireNonNull(builder.configMapKey, "configMapKey");
      this.classpathResource =
          Objects.requireNonNull(builder.classpathResource, "classpathResource");
    }

    public static Builder builder() {
      return new Builder();
    }

    public String configMapKey() {
      return configMapKey;
    }

    public String classpathResource() {
      return classpathResource;
    }

    public static final class Builder {
      @MonotonicNonNull private String configMapKey;
      @MonotonicNonNull private String classpathResource;

      private Builder() {}

      public Builder configMapKey(String configMapKey) {
        this.configMapKey = configMapKey;
        return this;
      }

      public Builder classpathResource(String classpathResource) {
        this.classpathResource = classpathResource;
        return this;
      }

      public ConfigMapAsset build() {
        return new ConfigMapAsset(this);
      }
    }
  }
}
