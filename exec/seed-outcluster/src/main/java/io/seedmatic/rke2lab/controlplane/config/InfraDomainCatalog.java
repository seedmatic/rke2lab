package io.seedmatic.rke2lab.controlplane.config;

import java.util.List;

/**
 * Canonical Stage-A (provisioning) domain-id catalog. Sibling to {@code ManifestDomainCatalog} at a
 * different layer: these ids tag provisioning concerns, not Kubernetes deployment domains. The
 * constants are the single source of truth; registrars reference them so an id is spelled once.
 */
public final class InfraDomainCatalog {

  public static final String INCUS = "incus";
  public static final String IMAGE = "image";
  public static final String NETWORK = "network";
  public static final String SYSTEMD = "systemd";
  public static final String HOST = "host";

  private static final List<String> ALL = List.of(INCUS, IMAGE, NETWORK, SYSTEMD, HOST);

  private InfraDomainCatalog() {}

  public static List<String> all() {
    return ALL;
  }

  public static boolean isKnownDomainId(String domainId) {
    return ALL.contains(domainId);
  }
}
