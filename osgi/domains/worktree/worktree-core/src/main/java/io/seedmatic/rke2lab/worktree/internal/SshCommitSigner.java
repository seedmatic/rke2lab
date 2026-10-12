package io.seedmatic.rke2lab.worktree.internal;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.lib.GpgConfig;
import org.eclipse.jgit.lib.GpgSignature;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.Signer;
import org.eclipse.jgit.transport.CredentialsProvider;

/**
 * A JGit {@link Signer} that SSH-signs a commit by shelling the local {@code ssh-keygen -Y sign}
 * with a caller-supplied OpenSSH private key — the git SSHSIG format under the {@code git}
 * namespace, exactly what the operator's {@code gpg.format=ssh} / {@code gpg.ssh.program} config
 * produces. JGit 7.x registers NO ssh signer of its own, so {@code worktree-core} injects this one
 * via {@code CommitCommand.setSigner}; the bot then signs with its own imported key (the ndh {@code
 * rke2lab-bot-signing}), not the ambient user's.
 *
 * <p>The private key is written to a {@code 0600} temp file for the single {@code sign} call and
 * deleted immediately after ({@code ssh-keygen -Y sign} needs a key FILE — it cannot read the key
 * from stdin, only the data to sign). {@code ssh-keygen} is resolved from {@code PATH} (the flox
 * runtime provides openssh); the data JGit hands us is signed verbatim, so {@code git} verifies the
 * commit against the same SSHSIG.
 */
final class SshCommitSigner implements Signer {

  private static final String NAMESPACE = "git";
  private static final Duration TIMEOUT = Duration.ofSeconds(20);

  private final String privateKey;

  SshCommitSigner(final String privateKey) {
    this.privateKey = privateKey;
  }

  @Override
  public boolean canLocateSigningKey(
      final Repository repository,
      final GpgConfig config,
      final PersonIdent committer,
      final String signingKey,
      final CredentialsProvider credentialsProvider) {
    return true; // the key is the one we hold; there is nothing to locate.
  }

  @Override
  public GpgSignature sign(
      final Repository repository,
      final GpgConfig config,
      final byte[] data,
      final PersonIdent committer,
      final String signingKey,
      final CredentialsProvider credentialsProvider)
      throws IOException {
    final Path keyFile = Files.createTempFile("rke2lab-git-sign-", ".key");
    try {
      Files.setPosixFilePermissions(
          keyFile, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
      Files.writeString(
          keyFile,
          privateKey.endsWith("\n") ? privateKey : privateKey + "\n",
          StandardCharsets.UTF_8);
      return new GpgSignature(runSshKeygen(keyFile, data));
    } finally {
      Files.deleteIfExists(keyFile);
    }
  }

  private byte[] runSshKeygen(final Path keyFile, final byte[] data) throws IOException {
    final Process process =
        new ProcessBuilder("ssh-keygen", "-Y", "sign", "-n", NAMESPACE, "-f", keyFile.toString())
            .start();
    try (OutputStream stdin = process.getOutputStream()) {
      stdin.write(data);
    }
    final byte[] signature = process.getInputStream().readAllBytes();
    final byte[] errors = process.getErrorStream().readAllBytes();
    try {
      if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        throw new IOException("ssh-keygen -Y sign timed out after " + TIMEOUT);
      }
    } catch (final InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted while ssh-signing the commit", ex);
    }
    if (process.exitValue() != 0) {
      throw new IOException(
          "ssh-keygen -Y sign failed ("
              + process.exitValue()
              + "): "
              + new String(errors, StandardCharsets.UTF_8).trim());
    }
    return signature;
  }
}
