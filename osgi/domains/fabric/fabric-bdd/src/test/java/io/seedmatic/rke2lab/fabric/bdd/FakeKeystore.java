package io.seedmatic.rke2lab.fabric.bdd;

import io.seedmatic.rke2lab.ndh.contract.NdhKeystoreReader;

/** A key-store that answers only what the delivery asks of it. */
final class FakeKeystore implements NdhKeystoreReader {
  @Override
  public boolean present() {
    return true;
  }

  @Override
  public String authorityCert(String authority) {
    throw new UnsupportedOperationException(authority);
  }

  @Override
  public String authorityDomain(String authority) {
    return "mammoth-skate.example.invalid";
  }

  @Override
  public String authorityPrivate(String authority) {
    throw new UnsupportedOperationException(authority);
  }

  @Override
  public String sshPrivate(String keyName) {
    return "-----BEGIN OPENSSH PRIVATE KEY-----\n" + keyName + "\n";
  }

  @Override
  public String sshPublic(String keyName) {
    throw new UnsupportedOperationException(keyName);
  }
}
