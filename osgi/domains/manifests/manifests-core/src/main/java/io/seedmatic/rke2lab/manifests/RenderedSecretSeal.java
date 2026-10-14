package io.seedmatic.rke2lab.manifests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.seedmatic.rke2lab.clusterpki.contract.SopsDecryptor;
import io.seedmatic.rke2lab.clusterpki.contract.SopsEncryptionException;
import io.seedmatic.rke2lab.clusterpki.contract.SopsEncryptor;
import io.seedmatic.rke2lab.manifests.contract.KeptLocalDirs;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Seals every Kubernetes Secret of a rendered tree IN MEMORY, before the tree is staged: each file
 * holding a Secret is piped through sops under the tree's own {@code .sops.yaml} and written back
 * as its envelope. No git filter takes part, so a blob that reaches git without a checkout carries
 * the same ciphertext as one staged by the CLI.
 *
 * <p>The policy decides HOW a file is sealed; this class decides THAT it is. A Secret is found by
 * its {@code kind} — at the top of a document, among a {@code List}'s items or a top-level array's,
 * in any file of the tree whatever its extension — never by its file name, and the seal fails
 * closed: once every file is sealed, any Secret whose {@code data}/{@code stringData} is not a map
 * of {@code ENC[…]} values refuses the render, and the files still in the clear are removed. That
 * is the failure the name-anchored policy rule allowed in silence — a namespaced Secret named
 * {@code 02-secret-*} matched no Kubernetes rule and sops wrapped its values, in the clear, in a
 * valid envelope.
 *
 * <p>Unchanged content keeps its committed ciphertext. sops takes a fresh IV and ephemeral key on
 * every encryption, so re-sealing an unchanged Secret is always a new blob — every render would
 * rewrite every Secret. Each Secret is sealed afresh under the current policy; the committed
 * envelope at the same path is written back instead only when it opens to the same documents AND
 * carries the same envelope — the same recipients, the same encryption rules — as the fresh one.
 * Comparing content alone would keep a Secret sealed for a recipient the policy has dropped, for as
 * long as its value does not change. Anything that cannot be compared is kept fresh: churn is the
 * price, staging stale ciphertext never is.
 */
public final class RenderedSecretSeal {

  private static final Logger LOG = LoggerFactory.getLogger(RenderedSecretSeal.class);
  private static final String POLICY = ".sops.yaml";
  private static final String SECRET_KIND = "Secret";
  private static final String LIST_KIND = "List";
  private static final List<String> VALUE_FIELDS = List.of("data", "stringData");
  private static final String ENVELOPE = "sops";
  // The render's own scratch, ignored by the committed .gitignore like .local.d.
  private static final Pattern STRUCTURED = Pattern.compile("\\.(ya?ml|json)$");
  private static final Pattern SECRET_KIND_TEXT =
      Pattern.compile("kind[\"']?\\s*:\\s*[\"']?Secret\\b");
  // What sops writes anew on every encryption: the MAC, the timestamps, the tool version and each
  // recipient's wrapped data key. Everything else in the envelope is what the policy decided.
  private static final Set<String> PER_ENCRYPTION = Set.of("mac", "lastmodified", "version");
  private static final Set<String> PER_RECIPIENT = Set.of("enc", "created_at");

  private final SopsEncryptor encryptor;
  private final SopsDecryptor decryptor;
  private final YamlMapper yaml;

  public RenderedSecretSeal(SopsEncryptor encryptor, SopsDecryptor decryptor, YamlMapper yaml) {
    this.encryptor = encryptor;
    this.decryptor = decryptor;
    this.yaml = yaml;
  }

  /**
   * Seal the Secrets under {@code root} and verify none is left in the clear. {@code committed}
   * returns the blob committed at a path relative to {@code root}, empty when there is none. A tree
   * with no Secret needs no policy; a tree with one and no {@code .sops.yaml} at its root is
   * refused.
   */
  public void seal(Path root, Function<String, Optional<String>> committed) {
    final Map<Scan, List<Path>> scanned = scan(root);
    final List<Path> unparseable = scanned.getOrDefault(Scan.UNPARSEABLE, List.of());
    if (!unparseable.isEmpty()) {
      throw refuse(
          root,
          unparseable,
          "rendered files that do not parse, so none can be shown to hold no Secret: "
              + unparseable.stream().map(file -> relative(root, file)).toList()
              + "; refusing to commit the render");
    }
    final List<Path> secrets = scanned.getOrDefault(Scan.SECRET, List.of());
    if (secrets.isEmpty()) {
      return;
    }
    final Path policy = root.resolve(POLICY);
    if (!Files.isRegularFile(policy)) {
      throw refuse(
          root,
          secrets,
          "the rendered tree holds Secrets but no " + POLICY + " at " + root + " to seal them");
    }
    final List<String> unopenable = new ArrayList<>();
    try {
      for (Path file : secrets) {
        sealOne(root, file, policy, committed, unopenable);
      }
    } catch (RuntimeException ex) {
      throw refuse(
          root,
          secrets.stream().filter(file -> !sealed(readString(file))).toList(),
          // Our own refusals name paths only; anything else — a parser's message — could quote a
          // value, so only its class is kept.
          "cannot seal the rendered Secrets: "
              + (ex instanceof IllegalStateException ? ex.getMessage() : ex.getClass().getName()));
    }
    if (!unopenable.isEmpty()) {
      LOG.warn(
          "{} Secrets re-sealed: their committed envelope does not open here, so their unchanged"
              + " content is committed as new ciphertext: {}",
          unopenable.size(),
          unopenable);
    }
    final List<Path> clear = secrets.stream().filter(file -> !sealed(readString(file))).toList();
    if (!clear.isEmpty()) {
      throw refuse(
          root,
          clear,
          "Secret values left in the clear after sealing under "
              + POLICY
              + " — no creation rule encrypts data/stringData for "
              + clear.stream().map(file -> relative(root, file)).toList()
              + "; refusing to commit the render");
    }
  }

  private void sealOne(
      Path root,
      Path file,
      Path policy,
      Function<String, Optional<String>> committed,
      List<String> unopenable) {
    final String path = relative(root, file);
    final String content = readString(file);
    final String plaintext = sealed(content) ? open(path, content) : content;
    final String fresh = encryptor.encryptYaml(plaintext, policy, path);
    final Optional<String> prior =
        committed.apply(path).or(() -> sealed(content) ? Optional.of(content) : Optional.empty());
    replace(
        file,
        prior
            .filter(blob -> sealed(blob) && sameEnvelope(blob, fresh))
            .filter(blob -> opensTo(blob, plaintext, path, unopenable))
            .orElse(fresh));
  }

  /**
   * Only a {@link SopsEncryptionException} lends its message, which never carries sops' stderr;
   * anything else — an I/O failure, another decryptor, a parser inside one — is named by its class.
   */
  private String open(String path, String sealedContent) {
    try {
      return decryptor.decryptYaml(sealedContent);
    } catch (SopsEncryptionException ex) {
      throw unopenable(path, String.valueOf(ex.getMessage()));
    } catch (RuntimeException ex) {
      throw unopenable(path, ex.getClass().getName());
    }
  }

  private static IllegalStateException unopenable(String path, String reason) {
    return new IllegalStateException(
        path
            + " arrives already sealed and cannot be opened here ("
            + reason
            + "), so it cannot be re-sealed under "
            + POLICY);
  }

  private boolean sameEnvelope(String committedBlob, String fresh) {
    try {
      return envelopes(committedBlob).equals(envelopes(fresh));
    } catch (RuntimeException ex) {
      return false;
    }
  }

  /** The policy-decided part of each document's sops envelope. */
  private List<JsonNode> envelopes(String sealedContent) {
    return documents(sealedContent)
        .map(document -> document.path(ENVELOPE).deepCopy())
        .map(
            envelope -> {
              if (envelope instanceof ObjectNode object) {
                object.remove(PER_ENCRYPTION);
                stripPerRecipient(object);
              }
              return (JsonNode) envelope;
            })
        .toList();
  }

  private static void stripPerRecipient(JsonNode node) {
    if (node instanceof ObjectNode object) {
      object.remove(PER_RECIPIENT);
    }
    node.forEach(RenderedSecretSeal::stripPerRecipient);
  }

  private boolean opensTo(String blob, String plaintext, String path, List<String> unopenable) {
    try {
      return documents(decryptor.decryptYaml(blob)).toList().equals(documents(plaintext).toList());
    } catch (RuntimeException ex) {
      // The class only: a parse failure's message can quote the offending token — a value.
      LOG.debug("the committed {} cannot be compared ({})", path, ex.getClass().getName());
      unopenable.add(path);
      return false;
    }
  }

  private IllegalStateException refuse(Path root, List<Path> clear, String message) {
    // No clean filter backs a hand `git add` in the rendered worktree any more: what was refused
    // must not stay there in the clear.
    clear.forEach(
        file -> {
          try {
            Files.deleteIfExists(file);
          } catch (IOException ex) {
            LOG.error("cannot remove the unsealed {}", relative(root, file));
          }
        });
    return new IllegalStateException(message);
  }

  /** What a rendered file is, as far as the seal is concerned. */
  private enum Scan {
    SECRET,
    NONE,
    UNPARSEABLE
  }

  private Map<Scan, List<Path>> scan(Path root) {
    try (Stream<Path> tree = Files.walk(root)) {
      return tree.filter(Files::isRegularFile)
          .filter(file -> !excluded(root, file))
          .sorted()
          .collect(Collectors.groupingBy(this::classify));
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot walk the rendered tree " + root, ex);
    }
  }

  private static boolean excluded(Path root, Path file) {
    if (POLICY.equals(file.getFileName().toString())) {
      return true;
    }
    final Path relative = root.relativize(file);
    for (Path segment : relative) {
      if (".git".equals(segment.toString())) {
        return true;
      }
    }
    return KeptLocalDirs.OF_A_WORKTREE.holds(relative);
  }

  /**
   * Whether {@code file} holds a Secret. A file that does not parse is no proof of absence: a
   * truncated write or a stray tab in a Secret would otherwise be staged as is. So a structured
   * file the render writes ({@code .yml}/{@code .yaml}/{@code .json}) that does not parse is
   * UNPARSEABLE, and so is any other file whose raw text declares a Secret kind; a genuinely
   * non-YAML file (a {@code .nix}, a binary) holds none.
   */
  private Scan classify(Path file) {
    final String content;
    try {
      content = readString(file);
    } catch (UncheckedIOException ex) {
      return SECRET_KIND_TEXT.matcher(readLatin1(file)).find() ? Scan.UNPARSEABLE : Scan.NONE;
    }
    try {
      return secrets(content).findAny().isPresent() ? Scan.SECRET : Scan.NONE;
    } catch (RuntimeException ex) {
      return STRUCTURED.matcher(file.getFileName().toString()).find()
              || SECRET_KIND_TEXT.matcher(content).find()
          ? Scan.UNPARSEABLE
          : Scan.NONE;
    }
  }

  private static String readLatin1(Path file) {
    try {
      return Files.readString(file, StandardCharsets.ISO_8859_1);
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot read " + file, ex);
    }
  }

  private boolean sealed(String content) {
    return secrets(content).allMatch(RenderedSecretSeal::valuesEncrypted);
  }

  private Stream<JsonNode> secrets(String content) {
    return documents(content)
        .flatMap(RenderedSecretSeal::resources)
        .filter(document -> SECRET_KIND.equals(document.path("kind").asText()));
  }

  /** The resources a document carries: itself, a {@code List}'s items, a top-level array's. */
  private static Stream<JsonNode> resources(JsonNode document) {
    if (document.isArray()) {
      return StreamSupport.stream(document.spliterator(), false)
          .flatMap(RenderedSecretSeal::resources);
    }
    if (LIST_KIND.equals(document.path("kind").asText())) {
      return StreamSupport.stream(document.path("items").spliterator(), false)
          .flatMap(RenderedSecretSeal::resources);
    }
    return Stream.of(document);
  }

  private static boolean valuesEncrypted(JsonNode secret) {
    return VALUE_FIELDS.stream()
        .map(secret::path)
        .allMatch(
            values ->
                values.isMissingNode()
                    || values.isNull()
                    || (values.isObject()
                        && StreamSupport.stream(values.spliterator(), false)
                            .allMatch(
                                value -> value.isTextual() && value.asText().startsWith("ENC["))));
  }

  private Stream<JsonNode> documents(String content) {
    return yaml.read(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))).nodes();
  }

  private static String readString(Path file) {
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot read " + file, ex);
    }
  }

  private static void replace(Path file, String content) {
    try {
      final Path temp = Files.createTempFile(file.getParent(), ".seal-", ".tmp");
      Files.writeString(temp, content, StandardCharsets.UTF_8);
      Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException ex) {
      throw new UncheckedIOException("cannot write the sealed " + file, ex);
    }
  }

  private static String relative(Path root, Path file) {
    return root.relativize(file).toString();
  }
}
