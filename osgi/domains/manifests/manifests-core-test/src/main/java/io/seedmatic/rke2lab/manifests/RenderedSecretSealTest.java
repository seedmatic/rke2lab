package io.seedmatic.rke2lab.manifests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.github.marschall.memoryfilesystem.MemoryFileSystemBuilder;
import io.seedmatic.rke2lab.clusterpki.contract.SopsDecryptor;
import io.seedmatic.rke2lab.clusterpki.contract.SopsEncryptionException;
import io.seedmatic.rke2lab.clusterpki.contract.SopsEncryptor;
import io.seedmatic.rke2lab.manifests.contract.BranchSecretCarrier;
import io.seedmatic.rke2lab.manifests.contract.ManifestAnnotation;
import io.seedmatic.rke2lab.manifests.contract.ManifestExplodeRequest;
import io.seedmatic.rke2lab.manifests.contract.ManifestExplodeService;
import io.seedmatic.rke2lab.manifests.contract.NodeBootstrapArtifact;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;

/**
 * The render's Secrets reach a branch ENCRYPTED, proven against the REPOSITORY's own {@code
 * .sops.yaml} and the names the exploder really gives: a namespaced Secret (named {@code
 * 02-secret-*}) and the in-cluster cellar carrier come out of {@link RenderedSecretSeal} with every
 * {@code data}/{@code stringData} value in {@code ENC[…]}, whatever they are later renamed to — and
 * a policy that encrypts neither refuses the render instead of committing an envelope around clear
 * values.
 *
 * <p>Real sops, real exploder, real seal, all acquired from the registry. Encrypting needs only the
 * recipients' PUBLIC keys, so no private key is involved.
 */
class RenderedSecretSealTest {

  private static final String POLICY = ".sops.yaml";
  private static final String MARKER = "LEAK7f3a";

  @Test
  void aNamespacedSecretAndTheCellarCarrierAreSealedUnderTheRepositoryPolicy(@TempDir Path tmp)
      throws IOException {
    assumeTrue(onPath("sops"), "needs sops on PATH");
    final Path tree = renderedTree(tmp);
    Files.copy(repositoryPolicy(), tree.resolve(POLICY));

    seal(service(SopsDecryptor.class)).seal(tree, path -> Optional.empty());

    final List<Path> secrets = secretFiles(tree);
    assertEquals(2, secrets.size(), "the namespaced Secret and the cellar carrier");
    assertTrue(
        secrets.stream().anyMatch(file -> file.getFileName().toString().startsWith("02-secret-")),
        "the exploder names a namespaced Secret 02-secret-*");
    assertTrue(secrets.contains(BranchSecretCarrier.IN_CLUSTER_CELLAR.in(tree)));
    for (Path file : secrets) {
      final Map<?, ?> secret = secretOf(file);
      final List<Object> values = values(secret);
      assertFalse(values.isEmpty(), tree.relativize(file) + " carries values");
      assertTrue(
          values.stream().allMatch(value -> String.valueOf(value).startsWith("ENC[")),
          tree.relativize(file) + " has every data/stringData value encrypted");
      assertFalse(
          String.valueOf(((Map<?, ?>) secret.get("metadata")).get("name")).startsWith("ENC["),
          tree.relativize(file) + " keeps its name readable for Flux");
      assertFalse(Files.readString(file).contains("dummy-"), "no clear value survives");
    }
  }

  @Test
  void theSealRunsOnAnInMemoryTreeAndNoSecretTouchesTheDisk() throws IOException {
    assumeTrue(onPath("sops"), "needs sops on PATH");
    try (FileSystem memory = MemoryFileSystemBuilder.newEmpty().build()) {
      final Path tree = renderedTree(memory.getPath("/render"));
      Files.copy(repositoryPolicy(), tree.resolve(POLICY));

      seal(service(SopsDecryptor.class)).seal(tree, path -> Optional.empty());

      final List<Path> secrets = secretFiles(tree);
      assertEquals(2, secrets.size(), "the namespaced Secret and the cellar carrier");
      for (Path file : secrets) {
        assertEquals(memory, file.getFileSystem(), "the tree never left memory");
        assertTrue(
            values(secretOf(file)).stream()
                .allMatch(value -> String.valueOf(value).startsWith("ENC[")),
            tree.relativize(file) + " has every data/stringData value encrypted");
        assertFalse(Files.readString(file).contains("dummy-"), "no clear value survives");
      }
    }
  }

  @Test
  void aPolicyThatEncryptsNoSecretRefusesTheRender(@TempDir Path tmp) throws IOException {
    assumeTrue(onPath("sops"), "needs sops on PATH");
    final Path tree = renderedTree(tmp);
    Files.writeString(
        tree.resolve(POLICY),
        "creation_rules:\n"
            + "  - path_regex: '.*'\n"
            + "    encrypted_comment_regex: 'sops:encrypted'\n"
            + "    age: "
            + anyRepositoryRecipient()
            + "\n");

    final IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () -> seal(service(SopsDecryptor.class)).seal(tree, path -> Optional.empty()));
    assertTrue(refused.getMessage().contains(BranchSecretCarrier.IN_CLUSTER_CELLAR.file()));
    assertTrue(refused.getMessage().contains("02-secret-"));
    assertFalse(refused.getMessage().contains("dummy-"), "the refusal never quotes a value");
    assertTrue(secretFiles(tree).isEmpty(), "the refused Secrets do not stay in the clear");
  }

  @Test
  void aSecretTheRuleCannotSeeIsRefusedWhateverItsShape(@TempDir Path tmp) throws IOException {
    assumeTrue(onPath("sops"), "needs sops on PATH");
    final Path tree = renderedTree(tmp);
    Files.copy(repositoryPolicy(), tree.resolve(POLICY));
    // A Secret written as JSON: no rule names it, and the scan does not stop at yml.
    Files.writeString(
        tree.resolve("workloads/probe/probe/02-secret-json.json"),
        "{\"apiVersion\":\"v1\",\"kind\":\"Secret\",\"metadata\":{\"name\":\"json\"},"
            + "\"stringData\":{\"token\":\"dummy-json\"}}\n");
    final IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () -> seal(service(SopsDecryptor.class)).seal(tree, path -> Optional.empty()));
    assertTrue(refused.getMessage().contains("02-secret-json.json"));
    assertFalse(Files.exists(tree.resolve("workloads/probe/probe/02-secret-json.json")));
  }

  @Test
  void aSecretInsideATopLevelArrayIsFoundAndRefused(@TempDir Path tmp) throws IOException {
    assumeTrue(onPath("sops"), "needs sops on PATH");
    final Path tree = tmp.resolve("rendered");
    Files.createDirectories(tree);
    Files.copy(repositoryPolicy(), tree.resolve(POLICY));
    final Path array = tree.resolve("02-secret-array.yml");
    Files.writeString(
        array,
        "- apiVersion: v1\n  kind: Secret\n  metadata:\n    name: array\n"
            + "  stringData:\n    token: dummy-array\n");
    // sops cannot seal a sequence at the root, so the Secret it carries is refused, not staged.
    assertThrows(
        IllegalStateException.class,
        () -> seal(service(SopsDecryptor.class)).seal(tree, path -> Optional.empty()));
    assertFalse(Files.exists(array), "the array's clear Secret does not stay in the tree");
  }

  @Test
  void aTruncatedSecretIsRefusedRatherThanStagedAsIs(@TempDir Path tmp) throws IOException {
    assumeTrue(onPath("sops"), "needs sops on PATH");
    final Path tree = renderedTree(tmp);
    Files.copy(repositoryPolicy(), tree.resolve(POLICY));
    final Path truncated = tree.resolve("workloads/probe/probe/02-secret-truncated.yml");
    Files.writeString(
        truncated,
        "apiVersion: v1\nkind: Secret\nmetadata:\n  name: truncated\nstringData:\n"
            + "  token: \"dummy-trunc");
    final IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () -> seal(service(SopsDecryptor.class)).seal(tree, path -> Optional.empty()));
    assertTrue(refused.getMessage().contains("02-secret-truncated.yml"));
    assertFalse(refused.getMessage().contains("dummy-"), "the refusal never quotes a value");
    assertFalse(Files.exists(truncated), "the unparseable file does not stay in the tree");
  }

  @Test
  void aSecretWhoseDataIsNotAMapIsRefused(@TempDir Path tmp) throws IOException {
    assumeTrue(onPath("sops"), "needs sops on PATH");
    final Path tree = tmp.resolve("rendered");
    Files.createDirectories(tree);
    Files.copy(repositoryPolicy(), tree.resolve(POLICY));
    Files.writeString(
        tree.resolve("02-secret-scalar.yml"),
        "apiVersion: v1\nkind: Secret\nmetadata:\n  name: scalar\ndata: dummy-scalar\n");
    assertThrows(
        IllegalStateException.class,
        () -> seal(service(SopsDecryptor.class)).seal(tree, path -> Optional.empty()));
  }

  @Test
  void aValueLessReplicaStubIsCommittedAsRenderedAndNeverSentToSops(@TempDir Path tmp)
      throws IOException {
    final Map<String, String> stubs =
        Map.of(
            "flox/02-secret-github-token.yml", stub("github-token", ""),
            "tekton/02-secret-empty-data.yml", stub("empty-data", "data: {}\n"),
            "tailscale/02-secret-null-values.yml",
                stub("null-values", "stringData:\n  token: null\n"));
    final Path tree = treeWith(tmp, "creation_rules: []\n", stubs);

    seal(failingWith(new IllegalStateException("a value-less stub was sent to sops to be opened")))
        .seal(tree, path -> Optional.empty());

    for (Map.Entry<String, String> file : stubs.entrySet()) {
      assertEquals(file.getValue(), Files.readString(tree.resolve(file.getKey())), file.getKey());
    }
  }

  @Test
  void aSopsEnvelopeHoldingAClearValueIsRefusedNamingTheKeyOnly(@TempDir Path tmp)
      throws IOException {
    final Path tree =
        treeWith(
            tmp,
            "creation_rules: []\n",
            Map.of(
                "probe-system/02-secret-damaged.yaml",
                stub("damaged", "stringData:\n  token: " + MARKER + "\n")
                    + "sops:\n  mac: ENC[AES256_GCM,data:x,type:str]\n"));
    final IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () -> seal(service(SopsDecryptor.class)).seal(tree, path -> Optional.empty()));
    assertTrue(refused.getMessage().contains("02-secret-damaged"), refused.getMessage());
    assertTrue(refused.getMessage().contains("stringData.token"), refused.getMessage());
    assertTrue(refused.getMessage().contains("damaged or hand-edited"), refused.getMessage());
    assertFalse(
        refused.getMessage().contains(MARKER), "the refusal names the key, never the value");
  }

  @Test
  void aSopsEnvelopeWithoutAMacIsRefused(@TempDir Path tmp) throws IOException {
    final Path tree =
        treeWith(
            tmp,
            "creation_rules: []\n",
            Map.of(
                "probe-system/02-secret-nomac.yaml",
                stub("nomac", "stringData:\n  token: ENC[AES256_GCM,data:x,type:str]\n")
                    + "sops:\n  version: 3.13.3\n"));
    final IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () -> seal(service(SopsDecryptor.class)).seal(tree, path -> Optional.empty()));
    assertTrue(refused.getMessage().contains("no mac"), refused.getMessage());
  }

  @Test
  void aStubBesideAClearSecretInOneFileSealsTheClearOneAndLeavesTheStubValueLess(@TempDir Path tmp)
      throws IOException, InterruptedException {
    assumeTrue(onPath("sops") && onPath("age-keygen"), "needs sops and age-keygen on PATH");
    final Path key = tmp.resolve("operator.txt");
    final String file = "probe-system/02-secret-pair.yaml";
    final Path tree =
        treeWith(
            tmp,
            policyFor(throwawayRecipient(key)),
            Map.of(
                file,
                stub("stub", "")
                    + "---\n"
                    + stub("clear", "stringData:\n  token: dummy-pair-token\n")));

    seal(decryptingWith(key)).seal(tree, path -> Optional.empty());

    final String sealed = Files.readString(tree.resolve(file));
    assertFalse(sealed.contains("dummy-pair-token"), "the clear Secret is sealed");
    assertTrue(sealed.contains("token: ENC["), "its value is an ENC[…]");
    assertTrue(sealed.contains("name: stub"), "the stub stays, readable");
    assertEquals(
        1,
        sealed
            .lines()
            .map(String::strip)
            .filter(line -> line.contains(": ENC[") && !line.startsWith("mac:"))
            .count(),
        "only the clear Secret's one value is encrypted");
  }

  @Test
  void anEncLookingValueWithoutSopsMetadataIsSealedNotTrusted(@TempDir Path tmp)
      throws IOException, InterruptedException {
    assumeTrue(onPath("sops") && onPath("age-keygen"), "needs sops and age-keygen on PATH");
    final Path key = tmp.resolve("operator.txt");
    final String file = "probe-system/02-secret-lookalike.yaml";
    final Path tree =
        treeWith(
            tmp,
            policyFor(throwawayRecipient(key)),
            Map.of(file, stub("lookalike", "stringData:\n  token: ENC[AES256_GCM,data:fake]\n")));

    seal(failingWith(new IllegalStateException("a lookalike was opened as sealed")))
        .seal(tree, path -> Optional.empty());

    final String sealed = Files.readString(tree.resolve(file));
    assertFalse(sealed.contains("data:fake"), "the lookalike went through sops");
    assertTrue(sealed.contains("mac:"), "and came back with its own envelope");
  }

  @Test
  void aLibrarysIllegalStateExceptionInTheSealIsNamedByItsClassAlone(@TempDir Path tmp)
      throws IOException {
    // Only RenderRefusal and SopsEncryptionException lend their message: an IllegalStateException
    // from a library is text the seal does not control, and could quote what it was handling.
    final Path tree =
        treeWith(
            tmp,
            "creation_rules: []\n",
            Map.of(
                "probe-system/02-secret-clear.yaml",
                stub("clear", "stringData:\n  token: dummy-clear\n")));
    final SopsEncryptor failing =
        new SopsEncryptor() {
          @Override
          public String encryptYaml(String plaintextYaml, List<String> ageRecipients) {
            throw new IllegalStateException(MARKER);
          }

          @Override
          public String encryptYaml(String plaintextYaml, Path policy, String path) {
            throw new IllegalStateException(MARKER);
          }
        };
    final IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () ->
                new RenderedSecretSeal(
                        failing, service(SopsDecryptor.class), service(YamlMapper.class))
                    .seal(tree, path -> Optional.empty()));
    assertTrue(
        refused.getMessage().contains(IllegalStateException.class.getName()), refused.getMessage());
    assertFalse(refused.getMessage().contains(MARKER), "the library's text is not lent");
  }

  @Test
  void aSealedArrivalThatDoesNotOpenIsRefusedByItsClassAloneWhenTheFailureIsNotSops(
      @TempDir Path tmp) throws IOException {
    final Path tree = sealedArrival(tmp);
    final IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () ->
                seal(failingWith(new UncheckedIOException(MARKER, new IOException(MARKER))))
                    .seal(tree, path -> Optional.empty()));
    assertTrue(refused.getMessage().contains("02-secret-arrived"), refused.getMessage());
    assertTrue(
        refused.getMessage().contains(UncheckedIOException.class.getName()), refused.getMessage());
    for (Throwable link = refused; link != null; link = link.getCause()) {
      assertFalse(String.valueOf(link.getMessage()).contains(MARKER), "a message quotes the cause");
    }
  }

  @Test
  void aSealedArrivalThatDoesNotOpenCarriesTheSopsEdgesReason(@TempDir Path tmp)
      throws IOException {
    final Path tree = sealedArrival(tmp);
    final String reason =
        "sops --decrypt exited with code 128 (no key opens the data key; 14 stderr lines)";
    final IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () ->
                seal(failingWith(new SopsEncryptionException(reason)))
                    .seal(tree, path -> Optional.empty()));
    assertTrue(refused.getMessage().contains("02-secret-arrived"), refused.getMessage());
    assertTrue(refused.getMessage().contains(reason), refused.getMessage());
  }

  @Test
  void aTreeWithSecretsAndNoPolicyIsRefused(@TempDir Path tmp) throws IOException {
    final Path tree = renderedTree(tmp);
    assertThrows(
        IllegalStateException.class,
        () -> seal(service(SopsDecryptor.class)).seal(tree, path -> Optional.empty()));
  }

  @Test
  void anUnchangedSecretKeepsItsCommittedCiphertextAndAChangedOneIsResealed(@TempDir Path tmp)
      throws IOException, InterruptedException {
    assumeTrue(onPath("sops") && onPath("age-keygen"), "needs sops and age-keygen on PATH");
    final Path key = tmp.resolve("operator.txt");
    final String operator = throwawayRecipient(key);
    final String cluster = throwawayRecipient(tmp.resolve("cluster.txt"));
    final String policy = policyFor(operator);
    final RenderedSecretSeal seal = seal(decryptingWith(key));

    final Path first = renderedTree(tmp.resolve("first"));
    Files.writeString(first.resolve(POLICY), policy);
    seal.seal(first, path -> Optional.empty());
    final Map<String, String> committed = contents(first);

    final Path again = renderedTree(tmp.resolve("again"));
    Files.writeString(again.resolve(POLICY), policy);
    seal.seal(again, path -> Optional.ofNullable(committed.get(path)));
    assertEquals(committed, contents(again), "an identical render keeps every committed blob");

    final Path changed = renderedTree(tmp.resolve("changed"));
    Files.writeString(changed.resolve(POLICY), policy);
    writeCarrier(changed, "dummy-other-envelopes");
    seal.seal(changed, path -> Optional.ofNullable(committed.get(path)));
    final Map<String, String> resealed = contents(changed);
    final String carrier = BranchSecretCarrier.IN_CLUSTER_CELLAR.file();
    assertFalse(committed.get(carrier).equals(resealed.get(carrier)), "a changed value reseals");
    resealed.remove(carrier);
    committed.keySet().stream()
        .filter(path -> !path.equals(carrier))
        .forEach(path -> assertEquals(committed.get(path), resealed.get(path), path));

    // The policy gains a recipient, the content does not change: the operator's key still opens
    // the committed blobs, yet each must be resealed for the recipients the policy now names.
    final Path rekeyed = renderedTree(tmp.resolve("rekeyed"));
    Files.writeString(rekeyed.resolve(POLICY), policyFor(operator + "," + cluster));
    seal.seal(rekeyed, path -> Optional.ofNullable(committed.get(path)));
    final Map<String, String> rotated = contents(rekeyed);
    for (String path : committed.keySet()) {
      assertFalse(committed.get(path).equals(rotated.get(path)), path + " is resealed");
      assertEquals(
          Set.of(operator, cluster),
          recipients(rekeyed.resolve(path)),
          path + " is sealed for exactly the policy's recipients");
    }
  }

  private static String throwawayRecipient(Path key) throws IOException, InterruptedException {
    new ProcessBuilder("age-keygen", "-o", key.toString())
        .redirectErrorStream(true)
        .start()
        .waitFor();
    final Matcher recipient = Pattern.compile("age1[a-z0-9]{58}").matcher(Files.readString(key));
    assertTrue(recipient.find());
    return recipient.group();
  }

  private static String policyFor(String recipients) {
    return "creation_rules:\n"
        + "  - path_regex: '(^|/)(0[12]-secret-[^/]*|\\.secret-[^/]*)\\.ya?ml$'\n"
        + "    encrypted_regex: '^(data|stringData)$'\n"
        + "    age: "
        + recipients
        + "\n";
  }

  private static Set<String> recipients(Path sealedFile) {
    final Map<?, ?> envelope = (Map<?, ?>) secretOf(sealedFile).get("sops");
    return ((List<?>) envelope.get("age"))
        .stream()
            .map(entry -> String.valueOf(((Map<?, ?>) entry).get("recipient")))
            .collect(Collectors.toSet());
  }

  /** A namespaced Secret and a ConfigMap through the real exploder, plus the cellar carrier. */
  private static Path renderedTree(Path tmp) throws IOException {
    final Path consolidated = tmp.resolve("synth.yaml");
    final Path tree = tmp.resolve("rendered");
    final Map<String, Object> secret = resource("Secret", "probe", "probe-system");
    secret.put("stringData", Map.of("token", "dummy-token"));
    secret.put("data", Map.of("key", "ZHVtbXkta2V5"));
    final Map<String, Object> configMap = resource("ConfigMap", "probe", "probe-system");
    configMap.put("data", Map.of("setting", "plain"));
    service(YamlMapper.class).write(consolidated).documents(List.of(secret, configMap));
    service(ManifestExplodeService.class).explode(new ManifestExplodeRequest(consolidated, tree));

    writeCarrier(tree, "dummy-envelopes");
    return tree;
  }

  private static void writeCarrier(Path tree, String envelopes) {
    final Map<String, Object> carrier = new LinkedHashMap<>();
    carrier.put("apiVersion", "v1");
    carrier.put("kind", "Secret");
    carrier.put(
        "metadata",
        Map.of(
            "name",
            "in-cluster-cellar",
            "annotations",
            Map.of("config.kubernetes.io/local-config", "true")));
    carrier.put("type", "Opaque");
    carrier.put("stringData", Map.of("envelopes", envelopes));
    service(YamlMapper.class)
        .write(BranchSecretCarrier.IN_CLUSTER_CELLAR.in(tree))
        .document(carrier);
  }

  /** A tree holding one Secret that arrives already sealed, so the seal must first open it. */
  private static Path sealedArrival(Path tmp) throws IOException {
    final Path tree = Files.createDirectories(tmp.resolve("rendered"));
    Files.writeString(tree.resolve(POLICY), "creation_rules: []\n");
    final Path arrived = tree.resolve("probe-system/02-secret-arrived.yaml");
    Files.createDirectories(arrived.getParent());
    Files.writeString(
        arrived,
        "apiVersion: v1\nkind: Secret\nmetadata:\n  name: arrived\n  namespace: probe-system\n"
            + "stringData:\n  token: ENC[AES256_GCM,data:x,type:str]\n"
            + "sops:\n  mac: ENC[AES256_GCM,data:x,type:str]\n");
    return tree;
  }

  /** A tree holding {@code files} (path → content) and a policy, as a render left it. */
  private static Path treeWith(Path tmp, String policy, Map<String, String> files)
      throws IOException {
    final Path tree = Files.createDirectories(tmp.resolve("rendered"));
    Files.writeString(tree.resolve(POLICY), policy);
    for (Map.Entry<String, String> file : files.entrySet()) {
      final Path target = tree.resolve(file.getKey());
      Files.createDirectories(target.getParent());
      Files.writeString(target, file.getValue());
    }
    return tree;
  }

  private static String stub(String name, String values) {
    return "apiVersion: v1\nkind: Secret\nmetadata:\n  name: "
        + name
        + "\n  namespace: probe-system\n"
        + values;
  }

  private static SopsDecryptor failingWith(RuntimeException failure) {
    return new SopsDecryptor() {
      @Override
      public String decryptYaml(String sopsYaml, String ageIdentity) {
        throw failure;
      }

      @Override
      public String decryptYaml(String sopsYaml) {
        throw failure;
      }
    };
  }

  private static RenderedSecretSeal seal(SopsDecryptor decryptor) {
    return new RenderedSecretSeal(
        service(SopsEncryptor.class), decryptor, service(YamlMapper.class));
  }

  /** The Secret files of a tree by their path relative to it, mutable for the test's checks. */
  private static Map<String, String> contents(Path tree) throws IOException {
    return secretFiles(tree).stream()
        .collect(
            Collectors.toMap(
                file -> tree.relativize(file).toString(),
                RenderedSecretSealTest::read,
                (a, b) -> a,
                LinkedHashMap::new));
  }

  private static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
  }

  /** A decryptor holding the test's throwaway key — the registry's one looks the key up itself. */
  private static SopsDecryptor decryptingWith(Path key) {
    return new SopsDecryptor() {
      @Override
      public String decryptYaml(String sopsYaml, String ageIdentity) {
        throw new UnsupportedOperationException("the seal opens with the ambient key");
      }

      @Override
      public String decryptYaml(String sopsYaml) {
        try {
          final ProcessBuilder pb =
              new ProcessBuilder(
                      "sops",
                      "--decrypt",
                      "--config",
                      "/dev/null",
                      "--input-type",
                      "yaml",
                      "--output-type",
                      "yaml",
                      "/dev/stdin")
                  .redirectError(ProcessBuilder.Redirect.DISCARD);
          pb.environment().put("SOPS_AGE_KEY_FILE", key.toString());
          final Process process = pb.start();
          try (var stdin = process.getOutputStream()) {
            stdin.write(sopsYaml.getBytes(StandardCharsets.UTF_8));
          }
          final String plaintext =
              new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
          if (process.waitFor() != 0) {
            throw new IllegalStateException("sops --decrypt failed");
          }
          return plaintext;
        } catch (IOException ex) {
          throw new UncheckedIOException(ex);
        } catch (InterruptedException ex) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(ex);
        }
      }
    };
  }

  private static Map<String, Object> resource(String kind, String name, String namespace) {
    final Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("name", name);
    metadata.put("namespace", namespace);
    metadata.put(
        "annotations",
        Map.of(
            ManifestAnnotation.DOMAIN.key(), "probe", ManifestAnnotation.PACKAGE.key(), "probe"));
    final Map<String, Object> document = new LinkedHashMap<>();
    document.put("apiVersion", "v1");
    document.put("kind", kind);
    document.put("metadata", metadata);
    return document;
  }

  private static List<Path> secretFiles(Path tree) throws IOException {
    try (Stream<Path> files = Files.walk(tree)) {
      return files
          .filter(Files::isRegularFile)
          .filter(file -> !tree.relativize(file).startsWith(NodeBootstrapArtifact.LOCAL_DIR))
          .filter(file -> file.getFileName().toString().endsWith(".yml"))
          .filter(file -> "Secret".equals(secretOf(file).get("kind")))
          .toList();
    }
  }

  private static Map<?, ?> secretOf(Path file) {
    return service(YamlMapper.class).read(file, Map.class);
  }

  private static List<Object> values(Map<?, ?> secret) {
    return Stream.of("data", "stringData")
        .map(secret::get)
        .filter(Map.class::isInstance)
        .flatMap(field -> ((Map<?, ?>) field).values().stream())
        .map(Object.class::cast)
        .toList();
  }

  /** The repository's own policy, found by walking up from the module to the checkout root. */
  private static Path repositoryPolicy() {
    for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
      if (Files.isRegularFile(dir.resolve(POLICY)) && Files.exists(dir.resolve(".git"))) {
        return dir.resolve(POLICY);
      }
    }
    throw new IllegalStateException("no " + POLICY + " at the root of the checkout");
  }

  private static String anyRepositoryRecipient() throws IOException {
    final Matcher recipient =
        Pattern.compile("age1[a-z0-9]{58}").matcher(Files.readString(repositoryPolicy()));
    assertTrue(recipient.find(), "the repository policy names an age recipient");
    return recipient.group();
  }

  private static <T> T service(Class<T> type) {
    final BundleContext context =
        FrameworkUtil.getBundle(RenderedSecretSealTest.class).getBundleContext();
    final var reference = context.getServiceReference(type);
    assertNotNull(reference, "the registry must publish " + type.getSimpleName());
    return context.getService(reference);
  }

  private static boolean onPath(String tool) {
    for (String dir : System.getenv().getOrDefault("PATH", "").split(":")) {
      if (Files.isExecutable(Path.of(dir, tool))) {
        return true;
      }
    }
    return false;
  }
}
