package io.seedmatic.rke2lab.seed.broker.codec;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.seedmatic.rke2lab.seed.broker.codec.internal.WireEnumModule;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.yaml.snakeyaml.LoaderOptions;

/**
 * The {@link SeedCodec#reading()} profile: parses JSON and YAML we do not write — config, secrets,
 * the files of other tools — and accepts every form found in them. An unknown key is ignored, the
 * same additive-schema tolerance as the seam. {@link CanonicalCodec} and {@link WireCodec} are
 * derived from this one, so they read exactly like it; they only add a writer.
 */
public sealed class ReadingCodec permits CanonicalCodec, WireCodec {

  private final JsonMapper json;
  private final YAMLMapper yaml;

  // Modules registered explicitly, never discovered: see SeedCodec.
  ReadingCodec() {
    // SnakeYAML caps a single document at 3 MiB; a synthesized manifest carries archives above it.
    final LoaderOptions loader = new LoaderOptions();
    loader.setCodePointLimit(64 * 1024 * 1024);
    this(
        JsonMapper.builder()
            .addModule(new WireEnumModule())
            .addModule(new JavaTimeModule())
            .addModule(new Jdk8Module())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build(),
        YAMLMapper.builder(YAMLFactory.builder().loaderOptions(loader).build())
            .addModule(new WireEnumModule())
            .addModule(new JavaTimeModule())
            .addModule(new Jdk8Module())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build());
  }

  ReadingCodec(JsonMapper json, YAMLMapper yaml) {
    this.json = json;
    this.yaml = yaml;
  }

  final JsonMapper json() {
    return json;
  }

  final YAMLMapper yaml() {
    return yaml;
  }

  public final JsonNode readJson(String text) {
    return read(json, text, "JSON");
  }

  public final <T> T readJson(String text, Class<T> type) {
    return read(json, text, type, "JSON");
  }

  public final <T> T readJson(String text, TypeReference<T> type) {
    return read(json, text, type, "JSON");
  }

  public final JsonNode readYaml(String text) {
    return read(yaml, text, "YAML");
  }

  public final <T> T readYaml(String text, Class<T> type) {
    return read(yaml, text, type, "YAML");
  }

  public final <T> T readYaml(String text, TypeReference<T> type) {
    return read(yaml, text, type, "YAML");
  }

  private JsonNode read(ObjectMapper mapper, String text, String format) {
    try {
      return mapper.readTree(text);
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed to read " + format, ex);
    }
  }

  private <T> T read(ObjectMapper mapper, String text, Class<T> type, String format) {
    try {
      return mapper.readValue(text, type);
    } catch (IOException ex) {
      throw new UncheckedIOException(
          "Failed to read " + format + " into " + type.getSimpleName(), ex);
    }
  }

  private <T> T read(ObjectMapper mapper, String text, TypeReference<T> type, String format) {
    try {
      return mapper.readValue(text, type);
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed to read " + format + " into " + type.getType(), ex);
    }
  }
}
