package io.seedmatic.rke2lab.clusterpki.contract;

/**
 * Unchecked failure of a sops edge — the tool is absent or exited non-zero. A failed seal is a
 * defect to surface fast, not a recoverable outcome, so it propagates uncaught (the {@link
 * SopsEncryptor} and {@link SopsDecryptor} seams document this).
 *
 * <p>Its message is SAFE to show: the operation, the exit code and a fixed classification, never
 * sops' stderr, whose parsers quote what they failed to read. So a caller may carry this message
 * into a failure reason — and only this one: any other exception from the seam stays its class.
 */
public final class SopsEncryptionException extends RuntimeException {

  public SopsEncryptionException(String message) {
    super(message);
  }

  public SopsEncryptionException(String message, Throwable cause) {
    super(message, cause);
  }
}
