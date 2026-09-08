// @codebase
package io.seedmatic.rke2lab.manifests.units.mesh;

import io.seedmatic.rke2lab.manifests.refs.ConfigMapRef;
import io.seedmatic.rke2lab.manifests.refs.SecretRef;
import io.seedmatic.rke2lab.manifests.units.ingress.IngressRefs;

/**
 * Shared mesh references (Headscale/Headplane configmaps + secrets) consumable independently of
 * resource realization. They live in the shared {@link IngressRefs#SYSTEM_NAMESPACE} — the mesh
 * control service co-locates with the ingress capability in one system namespace.
 */
public final class MeshRefs {

  public static final ConfigMapRef HEADPLANE_ENV_CONFIGMAP =
      ConfigMapRef.of(
          "mesh/headplane-env-configmap", IngressRefs.SYSTEM_NAMESPACE, "headplane-env");

  public static final ConfigMapRef HEADSCALE_CONFIG_CONFIGMAP =
      ConfigMapRef.of(
          "mesh/headscale-config-configmap", IngressRefs.SYSTEM_NAMESPACE, "headscale-config");

  public static final ConfigMapRef HEADSCALE_ENV_CONFIGMAP =
      ConfigMapRef.of(
          "mesh/headscale-env-configmap", IngressRefs.SYSTEM_NAMESPACE, "headscale-env");

  public static final SecretRef HEADSCALE_CLIENT_AUTH_SECRET =
      SecretRef.of(
          "mesh/headscale-client-auth-secret",
          IngressRefs.SYSTEM_NAMESPACE,
          "headscale-client-auth");

  public static final SecretRef HEADPLANE_SECRETS_SECRET =
      SecretRef.of(
          "mesh/headplane-secrets-secret", IngressRefs.SYSTEM_NAMESPACE, "headplane-secrets");

  private MeshRefs() {}
}
