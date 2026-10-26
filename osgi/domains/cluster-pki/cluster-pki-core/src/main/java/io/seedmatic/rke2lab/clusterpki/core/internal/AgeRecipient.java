package io.seedmatic.rke2lab.clusterpki.core.internal;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.NamedParameterSpec;
import java.security.spec.XECPrivateKeySpec;
import java.security.spec.XECPublicKeySpec;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.crypto.KeyAgreement;

/**
 * The age RECIPIENT ({@code age1…}) of a cluster-age identity ({@code AGE-SECRET-KEY-1…}), derived
 * in the JVM so the identity reaches no other process: the identity is bech32 for a 32-byte X25519
 * scalar, and its recipient is bech32 for the matching public. The JDK's XDH clamps the scalar on
 * import, as age does.
 *
 * <p>Nothing here quotes the identity: a refusal names neither it nor its length, and a decoding
 * error is raised here, never passed on from a library that might echo its input. The scalar lives
 * in a byte array that is zeroed once the public is derived.
 */
public record AgeRecipient(String recipient) {

  private static final String IDENTITY_HRP = "age-secret-key-";
  private static final String RECIPIENT_HRP = "age";
  private static final String CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l";
  private static final int KEY_BYTES = 32;
  private static final int[] GENERATOR = {
    0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3
  };

  /** The recipient of {@code identity}; refuses, without quoting it, anything that is not one. */
  public static AgeRecipient of(String identity) {
    final byte[] scalar = decode(identity.toLowerCase(Locale.ROOT), IDENTITY_HRP);
    try {
      return new AgeRecipient(encode(RECIPIENT_HRP, publicOf(scalar)));
    } finally {
      Arrays.fill(scalar, (byte) 0);
    }
  }

  /** Whether this recipient is among {@code recipients}, as {@code .sops.yaml} lists them. */
  public boolean isIn(List<String> recipients) {
    return recipients.contains(recipient);
  }

  /**
   * The X25519 public of {@code scalar}: its key agreement with the base point {@code u = 9}, whose
   * 32 bytes are the public's u-coordinate, little-endian — the form age encodes.
   */
  private static byte[] publicOf(byte[] scalar) {
    try {
      final KeyFactory factory = KeyFactory.getInstance("XDH");
      final PrivateKey key =
          factory.generatePrivate(new XECPrivateKeySpec(NamedParameterSpec.X25519, scalar));
      final PublicKey base =
          factory.generatePublic(
              new XECPublicKeySpec(NamedParameterSpec.X25519, BigInteger.valueOf(9)));
      final KeyAgreement agreement = KeyAgreement.getInstance("XDH");
      agreement.init(key);
      agreement.doPhase(base, true);
      return agreement.generateSecret();
    } catch (GeneralSecurityException e) {
      throw new IllegalArgumentException("the cluster-age identity is not an X25519 key");
    }
  }

  private static byte[] decode(String text, String hrp) {
    final int separator = text.lastIndexOf('1');
    if (separator != hrp.length() || !text.startsWith(hrp) || text.length() < hrp.length() + 7) {
      throw new IllegalArgumentException("the cluster-age identity is not an age identity");
    }
    final int[] data = new int[text.length() - separator - 1];
    for (int i = 0; i < data.length; i++) {
      final int value = CHARSET.indexOf(text.charAt(separator + 1 + i));
      if (value < 0) {
        throw new IllegalArgumentException("the cluster-age identity is not valid bech32");
      }
      data[i] = value;
    }
    if (polymod(concat(expand(hrp), data)) != 1) {
      throw new IllegalArgumentException("the cluster-age identity fails its bech32 checksum");
    }
    final byte[] bytes = convert(Arrays.copyOf(data, data.length - 6), 5, 8, false);
    if (bytes.length != KEY_BYTES) {
      throw new IllegalArgumentException("the cluster-age identity is not a 32-byte key");
    }
    return bytes;
  }

  private static String encode(String hrp, byte[] payload) {
    final int[] data = toInts(convert(toInts(payload), 8, 5, true));
    final int[] values = concat(expand(hrp), concat(data, new int[6]));
    final int mod = polymod(values) ^ 1;
    final StringBuilder out = new StringBuilder(hrp).append('1');
    for (int value : data) {
      out.append(CHARSET.charAt(value));
    }
    for (int i = 0; i < 6; i++) {
      out.append(CHARSET.charAt((mod >>> (5 * (5 - i))) & 31));
    }
    return out.toString();
  }

  private static int polymod(int[] values) {
    int chk = 1;
    for (int value : values) {
      final int top = chk >>> 25;
      chk = ((chk & 0x1ffffff) << 5) ^ value;
      for (int i = 0; i < 5; i++) {
        if (((top >>> i) & 1) != 0) {
          chk ^= GENERATOR[i];
        }
      }
    }
    return chk;
  }

  private static int[] expand(String hrp) {
    final int[] out = new int[hrp.length() * 2 + 1];
    for (int i = 0; i < hrp.length(); i++) {
      out[i] = hrp.charAt(i) >>> 5;
      out[i + hrp.length() + 1] = hrp.charAt(i) & 31;
    }
    return out;
  }

  private static byte[] convert(int[] data, int from, int to, boolean pad) {
    int acc = 0;
    int bits = 0;
    final int max = (1 << to) - 1;
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (int value : data) {
      acc = (acc << from) | value;
      bits += from;
      while (bits >= to) {
        bits -= to;
        out.write((acc >>> bits) & max);
      }
    }
    if (pad && bits > 0) {
      out.write((acc << (to - bits)) & max);
    } else if (!pad && (bits >= from || ((acc << (to - bits)) & max) != 0)) {
      throw new IllegalArgumentException("the cluster-age identity has bech32 padding errors");
    }
    return out.toByteArray();
  }

  private static int[] toInts(byte[] bytes) {
    final int[] out = new int[bytes.length];
    for (int i = 0; i < bytes.length; i++) {
      out[i] = bytes[i] & 0xff;
    }
    return out;
  }

  private static int[] concat(int[] a, int[] b) {
    final int[] out = Arrays.copyOf(a, a.length + b.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }
}
