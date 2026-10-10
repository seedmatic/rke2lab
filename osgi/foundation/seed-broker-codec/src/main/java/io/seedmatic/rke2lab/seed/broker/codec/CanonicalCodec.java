package io.seedmatic.rke2lab.seed.broker.codec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * The {@link SeedCodec#canonical()} profile: the one form of the files we write and read back, so
 * the same value always yields the same bytes and a diff shows only what changed. Map entries and
 * JSON trees are sorted by key; a record keeps its declared component order; an empty {@code
 * Optional} drops its key. JSON is indented by two spaces, {@code "k": v}, one element per line,
 * {@code \n} line ends; YAML has no document marker, quotes only what would not read back as the
 * same string, and writes multi-line strings as literal blocks. Both end with a newline.
 */
public final class CanonicalCodec extends ReadingCodec {

  private final ObjectWriter jsonWriter;

  CanonicalCodec(ReadingCodec reading) {
    final JsonInclude.Value nonAbsent =
        JsonInclude.Value.construct(
            JsonInclude.Include.NON_ABSENT, JsonInclude.Include.USE_DEFAULTS);
    this(
        new JsonMapper.Builder(reading.json().copy())
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(JsonNodeFeature.WRITE_PROPERTIES_SORTED)
            .defaultPropertyInclusion(nonAbsent)
            .build(),
        new YAMLMapper.Builder(reading.yaml().copy())
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(JsonNodeFeature.WRITE_PROPERTIES_SORTED)
            .defaultPropertyInclusion(nonAbsent)
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
            .disable(YAMLGenerator.Feature.SPLIT_LINES)
            .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
            .enable(YAMLGenerator.Feature.ALWAYS_QUOTE_NUMBERS_AS_STRINGS)
            .enable(YAMLGenerator.Feature.LITERAL_BLOCK_STYLE)
            .enable(YAMLGenerator.Feature.INDENT_ARRAYS_WITH_INDICATOR)
            .build());
  }

  private CanonicalCodec(JsonMapper json, YAMLMapper yaml) {
    super(json, yaml);
    final DefaultIndenter indent = new DefaultIndenter("  ", "\n");
    this.jsonWriter =
        json.writer(
            new DefaultPrettyPrinter()
                .withSeparators(
                    Separators.createDefaultInstance()
                        .withObjectFieldValueSpacing(Separators.Spacing.AFTER)
                        .withObjectEmptySeparator("")
                        .withArrayEmptySeparator(""))
                .withObjectIndenter(indent)
                .withArrayIndenter(indent));
  }

  public String writeJson(Object value) {
    try {
      return jsonWriter.writeValueAsString(value) + "\n";
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed to write canonical JSON", ex);
    }
  }

  public String writeYaml(Object value) {
    try {
      return yaml().writeValueAsString(value);
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed to write canonical YAML", ex);
    }
  }
}
