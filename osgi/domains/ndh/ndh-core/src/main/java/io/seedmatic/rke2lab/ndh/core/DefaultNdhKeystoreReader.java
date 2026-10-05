package io.seedmatic.rke2lab.ndh.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.seedmatic.rke2lab.ndh.contract.NdhKeystoreReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.osgi.service.component.annotations.Component;

/**
 * The realised ndh reader — the single door to the operator's key inventory ({@code
 * .ndh-ssh.d/keys.yaml}). Like {@code worktree-core} reads git, it reads the inventory: a pure-Java
 * contact, CWD-relative (the readers run in the host JVM rooted at the worktree, where the smudge
 * filter laid the plaintext copy). The YAML is parsed per call — it is tiny, and reading fresh
 * avoids any staleness across a run.
 *
 * <p>The inventory is ndh's v2 shape: key material lives in dated generations, {@code
 * <entry>.slots.<YY-MM-DD>.{public,private}}, one slot when settled and two while a renewal is in
 * flight. Which generation is read is NOT the same for both sections, and the asymmetry is ndh's,
 * not this reader's choice:
 *
 * <ul>
 *   <li>a KEY is read at its NEWEST slot — the generation it presents;
 *   <li>an AUTHORITY signs with its OLDEST slot — trust in a new authority distributes one
 *       activation at a time while a signature takes effect at once, so signing with the newest
 *       would hand an un-activated verifier a certificate it cannot check.
 * </ul>
 *
 * <p>{@code ca_crt} and {@code domain} stayed at the authority level. There is no fallback to the
 * v1 flat {@code public}/{@code private}: an entry without slots raises, naming the shape.
 *
 * <p>{@link #present()} is the fail-soft gate; the accessors are fail-fast — a
 * present-but-malformed store (unparseable, sops-encrypted at rest, or a missing field) raises, a
 * defect to surface.
 */
@Component(service = NdhKeystoreReader.class)
public final class DefaultNdhKeystoreReader implements NdhKeystoreReader {

  private static final Path DEFAULT_PATH = Path.of(".ndh-ssh.d/keys.yaml");

  private final Path path;

  public DefaultNdhKeystoreReader() {
    this(DEFAULT_PATH);
  }

  DefaultNdhKeystoreReader(final Path path) {
    this.path = path;
  }

  @Override
  public boolean present() {
    return Files.isReadable(path);
  }

  @Override
  public String authorityCert(String authority) {
    return text(read(), "authorities", authority, "ca_crt");
  }

  @Override
  public String authorityDomain(String authority) {
    return text(read(), "authorities", authority, "domain");
  }

  @Override
  public String authorityPrivate(String authority) {
    final JsonNode root = read();
    return text(
        root,
        "authorities",
        authority,
        "slots",
        oldestSlot(root, "authorities", authority),
        "private");
  }

  @Override
  public String sshPrivate(String keyName) {
    final JsonNode root = read();
    return text(root, "keys", keyName, "slots", newestSlot(root, "keys", keyName), "private");
  }

  @Override
  public String sshPublic(String keyName) {
    final JsonNode root = read();
    return text(root, "keys", keyName, "slots", newestSlot(root, "keys", keyName), "public");
  }

  private String newestSlot(final JsonNode root, final String section, final String name) {
    final List<String> slots = slots(root, section, name);
    return slots.get(slots.size() - 1);
  }

  private String oldestSlot(final JsonNode root, final String section, final String name) {
    return slots(root, section, name).get(0);
  }

  /** The entry's generations, oldest first — {@code YY-MM-DD} sorts as a date. */
  private List<String> slots(final JsonNode root, final String section, final String name) {
    final JsonNode entry = root.path(section).path(name);
    if (entry.isMissingNode()) {
      throw new IllegalStateException("missing '" + section + "." + name + "' in " + path);
    }
    final JsonNode slots = entry.path("slots");
    if (!slots.isObject() || slots.isEmpty()) {
      throw new IllegalStateException(
          "'"
              + section
              + "."
              + name
              + "' has no slots in "
              + path
              + " — the v1 flat public/private shape is not read; sync the ndh subtree");
    }
    final List<String> ids = new ArrayList<>();
    slots.fieldNames().forEachRemaining(ids::add);
    Collections.sort(ids);
    return ids;
  }

  private String text(final JsonNode root, final String... segments) {
    JsonNode node = root;
    for (String segment : segments) {
      node = node == null ? null : node.get(segment);
    }
    if (node == null || !node.isTextual() || node.asText().isBlank()) {
      throw new IllegalStateException(
          "missing/empty '" + String.join(".", segments) + "' in " + path);
    }
    return node.asText();
  }

  private JsonNode read() {
    if (!Files.isReadable(path)) {
      throw new IllegalStateException(
          "ndh key-store not readable at " + path + " (run from the worktree root; smudged?)");
    }
    final JsonNode root;
    try {
      root = new ObjectMapper(new YAMLFactory()).readTree(path.toFile());
    } catch (Exception ex) {
      throw new IllegalStateException("failed to parse " + path, ex);
    }
    if (root.has("sops")) {
      throw new IllegalStateException(
          path
              + " appears sops-encrypted at rest; the worktree copy must be plaintext "
              + "(check .gitattributes declares filter=sops-yaml and the smudge ran).");
    }
    return root;
  }
}
