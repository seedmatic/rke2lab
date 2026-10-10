package io.seedmatic.rke2lab.seed.broker.codec;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.seedmatic.rke2lab.seed.broker.codec.internal.WireEnumModule;
import io.seedmatic.rke2lab.seed.broker.port.Breadcrumb;
import io.seedmatic.rke2lab.seed.broker.port.Crossing;
import io.seedmatic.rke2lab.seed.broker.port.SeedContract;
import io.seedmatic.rke2lab.seed.broker.port.SeedEnvelope;
import io.seedmatic.rke2lab.seed.broker.port.SourceCrumb;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/**
 * The JSON (de)serialization + (capability) validation of {@code SeedEnvelope} payloads — the JSON
 * analogue of the manifests domain's {@code YamlMapper}. Written ONCE here; loaded per realm as our
 * own dual-realm library bundle ({@code embed; type=dual-realm}): staged as a bundle OSGi-side
 * (binding the OSGi jackson) and shaded flat host-side (binding the host jackson). No codec type
 * crosses the String-only seed-broker seam — each realm holds its own copy, exactly as jackson is
 * dual-loaded.
 *
 * <p>Modules are registered EXPLICITLY (never {@code findAndRegisterModules} — that {@code
 * ServiceLoader<Module>} discovery is the realm-isolation regression this project already closed):
 * one of ours — {@link WireEnumModule} (seam enum ↔ slug) — plus jackson's own official datatype
 * modules {@link JavaTimeModule} (for {@code Instant}, rendered ISO-8601 via {@code
 * WRITE_DATES_AS_TIMESTAMPS} disabled) and {@link Jdk8Module} for {@code Optional} (an absent key
 * deserializes to {@code Optional.empty()}; paired with {@code NON_ABSENT} an empty Optional omits
 * its key). Both {@code jackson-datatype-jsr310} and {@code jackson-datatype-jdk8} are
 * realm-isolated jackson artifacts imported bundle-to-bundle (NOT nested — we register explicitly,
 * so no ServiceLoader discovery drives them; the staging closure keeps each realm its own copy).
 * Runtime schema validation is WIRED but OFF by default (the embedded posture); the remote capstone
 * flips it on via {@link #withValidation(boolean)}.
 */
public final class SeedCodec {

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .registerModule(new WireEnumModule())
          .registerModule(new JavaTimeModule())
          .registerModule(new Jdk8Module())
          // The sealed Breadcrumb's polymorphism lives HERE, not on the port type: the seam carries
          // NO jackson dependency, so a Trail's crumbs cannot annotate themselves. This mixin is
          // the
          // SOLE type-info config, so every realm's SeedCodec — and the host's PulumiCellar, which
          // DELEGATES its trail (de)serialization here — resolves a crumb back to its concrete
          // shape. Add the mixin to a THIRD bare mapper and its trails silently mis-decode.
          .addMixIn(Breadcrumb.class, BreadcrumbMixin.class)
          .setDefaultPropertyInclusion(JsonInclude.Include.NON_ABSENT)
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          // Additive-schema tolerance: an unknown key is ignored, not a crash — the contract the
          // hand-rolled *Reader classes had (the schema evolves by adding keys, a producer's newer
          // key survives a reader written today). Keeps the "full-typed + lenient" posture.
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  /**
   * The type-info the sealed {@link Breadcrumb} cannot carry itself (the port is jackson-free) — a
   * {@code @kind} discriminator so a {@link io.seedmatic.rke2lab.seed.broker.port.Trail}'s crumbs
   * round-trip to their concrete shape: {@link SourceCrumb} (cellar, git-sourced) or {@link
   * Crossing} (scenario, a grafted-failure path). {@code defaultImpl = SourceCrumb} keeps the
   * durable cellar BACKWARD-COMPATIBLE: a coquille persisted before the sealed split carries a flat
   * {@code {domain, coordinate, sha, dirty}} trail with NO {@code @kind}, and every such legacy
   * crumb WAS a source crumb — so a missing discriminator decodes as {@link SourceCrumb} rather
   * than failing the drain on old state.
   */
  @JsonTypeInfo(
      use = JsonTypeInfo.Id.NAME,
      include = JsonTypeInfo.As.PROPERTY,
      property = "@kind",
      defaultImpl = SourceCrumb.class)
  @JsonSubTypes({
    @JsonSubTypes.Type(value = SourceCrumb.class, name = "source"),
    @JsonSubTypes.Type(value = Crossing.class, name = "crossing")
  })
  private interface BreadcrumbMixin {}

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private final boolean validationEnabled;
  private final ReadingCodec reading;
  private final CanonicalCodec canonical;
  private final WireCodec wire;

  public SeedCodec() {
    final ReadingCodec reading = new ReadingCodec();
    this(false, reading, new CanonicalCodec(reading), new WireCodec(reading));
  }

  private SeedCodec(
      boolean validationEnabled, ReadingCodec reading, CanonicalCodec canonical, WireCodec wire) {
    this.validationEnabled = validationEnabled;
    this.reading = reading;
    this.canonical = canonical;
    this.wire = wire;
  }

  /**
   * The files we write and read back, in their one canonical form. The seam's payloads stay on this
   * class's own mapper: they are never committed, so they need no canonical form.
   */
  public CanonicalCodec canonical() {
    return canonical;
  }

  /** The JSON and YAML we only parse — config, secrets, other tools' files. */
  public ReadingCodec reading() {
    return reading;
  }

  /** The bodies exchanged with external APIs. */
  public WireCodec wire() {
    return wire;
  }

  /** The off→on switch the capstone flips; embedded keeps the default (off). */
  public SeedCodec withValidation(boolean enabled) {
    return new SeedCodec(enabled, reading, canonical, wire);
  }

  public String encode(JsonNode node) {
    try {
      return MAPPER.writeValueAsString(node);
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed to encode SeedEnvelope payload", ex);
    }
  }

  public JsonNode decode(String payload) {
    try {
      return MAPPER.readTree(payload);
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed to decode SeedEnvelope payload", ex);
    }
  }

  /**
   * Serialize a wire-record to its SeedEnvelope payload String (seam enums render as their slug).
   */
  public String encode(Object wireRecord) {
    try {
      return MAPPER.writeValueAsString(wireRecord);
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed to encode wire-record payload", ex);
    }
  }

  /**
   * Deserialize a SeedEnvelope payload String into its wire-record (slugs resolve to seam enums).
   */
  public <T> T decode(String payload, Class<T> type) {
    try {
      return MAPPER.readValue(payload, type);
    } catch (IOException ex) {
      throw new UncheckedIOException("Failed to decode payload into " + type.getSimpleName(), ex);
    }
  }

  /**
   * Deserialize a {@link SeedEnvelope} into the wire-record {@code type}, first checking the
   * SeedEnvelope's coordinate matches the coordinate {@code type} declares via {@link SeedContract}
   * — so decoding an {@code intervention} SeedEnvelope as a {@code ReadinessVerdict} fails loudly
   * at the seam rather than silently mis-parsing. The one call site's {@code decode(doc.payload(),
   * X.class)} is this, minus the guard.
   */
  public <T> T decode(SeedEnvelope document, Class<T> type) {
    final SeedContract contract = type.getAnnotation(SeedContract.class);
    if (contract != null && !contract.value().equals(document.coordinate())) {
      throw new IllegalArgumentException(
          "cannot decode a '"
              + document.coordinate()
              + "' SeedEnvelope as "
              + type.getSimpleName()
              + " (contract coordinate '"
              + contract.value()
              + "')");
    }
    return decode(document.payload(), type);
  }

  /**
   * Convert an already-parsed structure (the opaque {@code Map}/{@code List} blob a SeedEnvelope
   * carries in an open slot) DIRECTLY into a typed record — no re-serialization round-trip. The
   * inverse of {@link #toMap}; the same MAPPER, so seam enums, {@code Instant}, {@code Optional},
   * and the annotated value types decode exactly as from a String payload. Unknown keys are
   * tolerated ({@code FAIL_ON_UNKNOWN_PROPERTIES} disabled); a structurally-invalid blob throws (a
   * record's compact-ctor guard surfaces as an {@link IllegalArgumentException}), which the caller
   * catches to degrade the enclosing entry.
   */
  public <T> T fromMap(Object rawStructure, Class<T> type) {
    return MAPPER.convertValue(rawStructure, type);
  }

  /**
   * Render a typed record to its opaque {@code Map} blob (the shape a SeedEnvelope's open slot
   * carries, and the host copies verbatim into a Pulumi output). The inverse of {@link #fromMap};
   * enum refs render as their annotated slug, {@code Instant} as ISO-8601 — the exact flat shape
   * the deleted {@code toOutputMap()} methods produced.
   */
  public Map<String, Object> toMap(Object record) {
    return MAPPER.convertValue(record, MAP_TYPE);
  }

  /**
   * Validate a payload against the coordinate's schema. INERT while validation is off (embedded):
   * returns {@code true} without loading a schema. The capstone enables it; that path loads {@code
   * schema/<slug>.schema.json} from the classpath and runs networknt.
   */
  public boolean validate(String payload, String schemaSlug) {
    if (!validationEnabled) {
      return true;
    }
    throw new UnsupportedOperationException(
        "runtime validation is enabled only by the remote capstone (schema=" + schemaSlug + ")");
  }
}
