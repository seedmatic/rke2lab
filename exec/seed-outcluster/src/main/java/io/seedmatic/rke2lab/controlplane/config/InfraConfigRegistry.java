package io.seedmatic.rke2lab.controlplane.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Aggregates the infra fragments each {@link InfraDomain} contributes, keyed by its {@link
 * InfraDomainCatalog} id. The single cast lives in {@link #fragment(String, Class)}; safe because
 * {@link InfraConfigFragment} is sealed.
 */
public final class InfraConfigRegistry {

  private final Map<String, InfraConfigFragment> fragmentsById;

  private InfraConfigRegistry(Map<String, InfraConfigFragment> fragmentsById) {
    this.fragmentsById = Map.copyOf(fragmentsById);
  }

  /** Every infra domain contributes its fragment via the loader. {@code values()} is the list. */
  static InfraConfigRegistry from(ConfigLoader loader) {
    final Map<String, InfraConfigFragment> fragmentsById = new LinkedHashMap<>();
    for (InfraDomain domain : InfraDomain.values()) {
      fragmentsById.put(domain.domainId(), domain.contribute(loader));
    }
    return new InfraConfigRegistry(fragmentsById);
  }

  public <T extends InfraConfigFragment> T fragment(String domainId, Class<T> type) {
    final InfraConfigFragment fragment = fragmentsById.get(domainId);
    if (fragment == null) {
      throw new IllegalStateException("No infra fragment contributed for domain: " + domainId);
    }
    return type.cast(fragment);
  }
}
