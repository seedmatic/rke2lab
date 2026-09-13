package io.seedmatic.rke2lab.controlplane.config;

/**
 * The fixed set of Stage-A (provisioning) infra domains. Each constant IS a registrar: it knows its
 * {@link InfraDomainCatalog} id and how to load its own {@link InfraConfigFragment} from config.
 *
 * <p>A closed set, so an enum fits better than separate registrar classes: {@link #values()} is the
 * complete, self-maintaining registration list (no hand-chained {@code register(new …)}, no
 * forgotten domain), uniqueness is free, and every domain reads top-to-bottom in one place.
 *
 * <p>Increment 2 (doctor) adds a per-constant {@code specialist()} so a domain owns both its config
 * fragment and its doctor specialist — see the config restructuring spec.
 */
public enum InfraDomain {
  INCUS(InfraDomainCatalog.INCUS) {
    @Override
    InfraConfigFragment contribute(ConfigLoader loader) {
      return new Rke2labConfig.IncusConfig(
          loader.optional(domainId(), "project"),
          loader.optionalUri(domainId(), "remoteAddress"),
          loader.requirePath(domainId(), "configDir"));
    }
  },

  IMAGE(InfraDomainCatalog.IMAGE) {
    @Override
    InfraConfigFragment contribute(ConfigLoader loader) {
      return new Rke2labConfig.ImageConfig(loader.optional(domainId(), "builderHost"));
    }
  },

  NETWORK(InfraDomainCatalog.NETWORK) {
    @Override
    InfraConfigFragment contribute(ConfigLoader loader) {
      return new Rke2labConfig.NetworkConfig(
          loader.optional(domainId(), "lanBridgeParent"),
          loader.optionalBoolean(domainId(), "automount"),
          loader.optional(domainId(), "tailnet"));
    }
  },

  SYSTEMD(InfraDomainCatalog.SYSTEMD) {
    @Override
    InfraConfigFragment contribute(ConfigLoader loader) {
      return new Rke2labConfig.SystemdAdapterConfig(
          loader.optional(domainId(), "dbusHost"), loader.optionalInt(domainId(), "dbusPort"));
    }
  },

  HOST(InfraDomainCatalog.HOST) {
    @Override
    InfraConfigFragment contribute(ConfigLoader loader) {
      return new Rke2labConfig.HostAssetConfig(
          loader.optionalInt(domainId(), "rotationRetentionCount"));
    }
  };

  private final String id;

  InfraDomain(String id) {
    this.id = id;
  }

  String domainId() {
    return id;
  }

  abstract InfraConfigFragment contribute(ConfigLoader loader);
}
