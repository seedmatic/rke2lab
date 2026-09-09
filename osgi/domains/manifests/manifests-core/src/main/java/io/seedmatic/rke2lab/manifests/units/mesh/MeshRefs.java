// @codebase
package io.seedmatic.rke2lab.manifests.units.mesh;

import io.seedmatic.rke2lab.manifests.refs.ConfigMapRef;
import io.seedmatic.rke2lab.manifests.refs.NamespaceRef;
import io.seedmatic.rke2lab.manifests.refs.SecretRef;

/**
 * Shared mesh references (the {@code mesh-system} namespace + Headscale/Headplane configmaps and
 * secrets) consumable independently of resource realization. The mesh control service owns its OWN
 * namespace — it no longer co-locates with the tailscale substrate (that split dissolved the shared
 * system namespace).
 */
public final class MeshRefs {

  public static final NamespaceRef SYSTEM_NAMESPACE =
      NamespaceRef.of("mesh/system-namespace", "mesh-system");

  public static final ConfigMapRef HEADPLANE_ENV_CONFIGMAP =
      ConfigMapRef.of("mesh/headplane-env-configmap", SYSTEM_NAMESPACE, "headplane-env");

  public static final ConfigMapRef HEADSCALE_CONFIG_CONFIGMAP =
      ConfigMapRef.of("mesh/headscale-config-configmap", SYSTEM_NAMESPACE, "headscale-config");

  public static final ConfigMapRef HEADSCALE_ENV_CONFIGMAP =
      ConfigMapRef.of("mesh/headscale-env-configmap", SYSTEM_NAMESPACE, "headscale-env");

  public static final SecretRef HEADSCALE_CLIENT_AUTH_SECRET =
      SecretRef.of("mesh/headscale-client-auth-secret", SYSTEM_NAMESPACE, "headscale-client-auth");

  public static final SecretRef HEADPLANE_SECRETS_SECRET =
      SecretRef.of("mesh/headplane-secrets-secret", SYSTEM_NAMESPACE, "headplane-secrets");

  private MeshRefs() {}
}
