package io.seedmatic.rke2lab.manifests;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.seedmatic.rke2lab.seed.broker.codec.SeedCodec;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.osgi.service.component.annotations.Component;

/**
 * The YAML (de)serialization service for the manifest pipeline — a single OSGi {@code @Component}
 * every YAML-emitting path goes through. DS consumers reach it by {@code @Reference}; the CDK8s
 * units (not components) reach it through the {@link ManifestsUnitContext} the synthesizer hands
 * each unit. A plain {@code new YamlMapper()} is also valid (tests, ad-hoc use).
 *
 * <p>It writes through the codec's {@code canonical()} profile and reads through its {@code
 * reading()} profile, so the rendering guarantees are the codec's, stated and tested there once:
 * keys sorted (a {@code Map.of(...)}'s hash order never churns a checksum), multi-line strings as
 * literal blocks, numeric-looking strings quoted (an {@code EnvVar.value} of {@code "6443"} stays a
 * string for the API server), what a YAML 1.1 reader would misread quoted, documents above
 * SnakeYAML's 3 MiB default read, and the empty documents of a stray {@code ---} skipped.
 *
 * <p>Do not introduce a mapper or a {@code StringBuilder yaml.append("---\n")} writer — extend this
 * service, or the codec, instead. I/O failures surface as {@link UncheckedIOException} (the repo
 * convention), keeping the read/write API usable inside fluent lambda chains.
 */
@Component(service = YamlMapper.class)
public final class YamlMapper {

  private final SeedCodec codec = new SeedCodec();

  /** Render a single document to a YAML string. */
  public String dump(Object document) {
    return codec.canonical().writeYaml(document);
  }

  /** Read a single document of {@code type} from {@code source}. */
  public <T> T read(Path source, Class<T> type) {
    try {
      return codec.reading().readYaml(Files.readString(source, StandardCharsets.UTF_8), type);
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed to read YAML from " + source, ex);
    }
  }

  /** Begin a fluent write to {@code target}; parent directories are created on write. */
  public Write write(Path target) {
    return new Write(target);
  }

  /** Begin a fluent read from a file {@code source}. */
  public Read read(Path source) {
    return new Read(() -> Files.newInputStream(source), source);
  }

  /** Begin a fluent read from an arbitrary stream (e.g. a classpath resource). */
  public Read read(InputStream source) {
    return new Read(() -> source, source);
  }

  /** Fluent writer over {@code target}. */
  public final class Write {
    private final Path target;

    private Write(Path target) {
      this.target = target;
    }

    /** Write a single document, creating parent directories if needed. */
    public void document(Object document) {
      writeString(dump(document));
    }

    /** Write a sequence of documents as one multi-doc YAML stream, separated by {@code ---}. */
    public void documents(Iterable<?> documents) {
      writeString(codec.canonical().writeYamlDocuments(documents));
    }

    private void writeString(String content) {
      try {
        if (target.getParent() != null) {
          Files.createDirectories(target.getParent());
        }
        Files.writeString(target, content, StandardCharsets.UTF_8);
      } catch (IOException ex) {
        throw new UncheckedIOException("Failed to write YAML to " + target, ex);
      }
    }
  }

  /** Fluent multi-doc reader; empty documents are filtered automatically. */
  public final class Read {
    private final Opener source;
    private final Object origin;

    private Read(Opener source, Object origin) {
      this.source = source;
      this.origin = origin;
    }

    /** Stream the documents as {@link JsonNode} trees. */
    public Stream<JsonNode> nodes() {
      return as(JsonNode.class);
    }

    /** Stream the documents as {@code type}, skipping the empty ones. */
    public <T> Stream<T> as(Class<T> type) {
      try {
        return codec.reading().readYamlDocuments(source.open(), type);
      } catch (IOException | UncheckedIOException ex) {
        throw new UncheckedIOException(
            "Failed to read YAML from " + origin, new IOException(ex.getMessage(), ex));
      }
    }

    /** Stream the documents as a parameterized {@code type} (e.g. {@code Map<String,Object>}). */
    public <T> Stream<T> as(TypeReference<T> type) {
      try {
        return codec.reading().readYamlDocuments(source.open(), type);
      } catch (IOException | UncheckedIOException ex) {
        throw new UncheckedIOException(
            "Failed to read YAML from " + origin, new IOException(ex.getMessage(), ex));
      }
    }
  }

  @FunctionalInterface
  private interface Opener {
    InputStream open() throws IOException;
  }
}
