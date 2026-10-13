package io.seedmatic.rke2lab.sops.edge;

import io.seedmatic.rke2lab.clusterpki.contract.SopsEncryptor;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The realised {@code sops} edge: seals a plaintext YAML by piping it through {@code sops
 * --encrypt}. The whole adapter — the single door toward this one external tool.
 *
 * <p>Two shapes. For explicit recipients, {@code --age <r1,r2> --config /dev/null}: {@code
 * /dev/null} is load-bearing, without it sops discovers the repo's {@code .sops.yaml}, whose {@code
 * encrypted_comment_regex} rule would leave the bundle in the CLEAR; {@code /dev/null} forces the
 * default encrypt-everything. Under a policy, {@code --config <policy> --filename-override <path>}:
 * the matching creation rule decides, exactly as for a file sops encrypts in place.
 */
@Component(service = SopsEncryptor.class)
public final class ProcessBuilderSopsEncryptor implements SopsEncryptor {

  private static final Logger LOG = LoggerFactory.getLogger(ProcessBuilderSopsEncryptor.class);

  private final SopsProcess sops = new SopsProcess();

  @Override
  public String encryptYaml(String plaintextYaml, List<String> ageRecipients) {
    if (ageRecipients == null || ageRecipients.isEmpty()) {
      throw new SopsEncryptionException("no age recipients — refusing to seal for nobody");
    }
    final String sealed =
        sops.run(
            List.of(
                "--encrypt",
                "--age",
                String.join(",", ageRecipients),
                "--config",
                "/dev/null",
                "--input-type",
                "yaml",
                "--output-type",
                "yaml",
                "/dev/stdin"),
            plaintextYaml);
    LOG.debug(
        "sops sealed a {}-byte bundle for {} recipients", sealed.length(), ageRecipients.size());
    return sealed;
  }

  @Override
  public String encryptYaml(String plaintextYaml, Path policy, String path) {
    if (policy.getFileSystem().equals(FileSystems.getDefault())) {
      return encryptUnder(plaintextYaml, policy, path);
    }
    // A policy on another file system (the render's in-memory tree) is unreadable to the sops
    // process: give it a real copy, owner-only, in its own directory so the relative path the
    // creation rules match keeps its meaning — and remove both whatever happens. The policy is
    // not secret; the document still goes in on stdin and never touches the disk.
    final Path dir = createPrivateDir();
    final Path copy = dir.resolve(policy.getFileName().toString());
    try {
      Files.write(copy, Files.readAllBytes(policy));
      Files.setPosixFilePermissions(copy, OWNER_READ_WRITE);
      return encryptUnder(plaintextYaml, copy, path);
    } catch (IOException ex) {
      throw new SopsEncryptionException("cannot hand the sops policy to the sops process", ex);
    } finally {
      deleteQuietly(copy);
      deleteQuietly(dir);
    }
  }

  private String encryptUnder(String plaintextYaml, Path policy, String path) {
    final String sealed =
        sops.run(
            List.of(
                "--encrypt",
                "--config",
                policy.toString(),
                "--filename-override",
                policy.toAbsolutePath().getParent().resolve(path).toString(),
                "--input-type",
                "yaml",
                "--output-type",
                "yaml",
                "/dev/stdin"),
            plaintextYaml);
    LOG.debug("sops sealed {} under {}", path, policy);
    return sealed;
  }

  private static Path createPrivateDir() {
    try {
      return Files.createTempDirectory(
          "rke2lab-sops-policy-",
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    } catch (IOException ex) {
      throw new SopsEncryptionException("cannot create a private dir for the sops policy", ex);
    }
  }

  private static void deleteQuietly(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // A leftover owner-only copy of a non-secret policy; the seal's outcome already stands.
    }
  }

  private static final Set<PosixFilePermission> OWNER_READ_WRITE =
      Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
}
