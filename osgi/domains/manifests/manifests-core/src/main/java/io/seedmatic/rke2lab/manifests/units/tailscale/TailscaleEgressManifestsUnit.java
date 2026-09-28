package io.seedmatic.rke2lab.manifests.units.tailscale;

import io.seedmatic.rke2lab.manifests.AbstractManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainCatalog;
import io.seedmatic.rke2lab.manifests.node.DefaultNodeEnvContext;
import io.seedmatic.rke2lab.manifests.profiles.PackageMetadataProfile;
import java.util.List;
import java.util.Map;
import org.cdk8s.ApiObject;
import org.cdk8s.ApiObjectMetadata;
import org.cdk8s.ApiObjectProps;
import software.constructs.Construct;

/**
 * Renders this cluster's egress {@code ProxyGroup} — the relay that gives pod-originated traffic a
 * TAILNET IDENTITY, so an external VIP becomes routable from inside the cluster.
 *
 * <p>Why it is needed at all, measured 2026-09-28 from a pod on {@code bioskop-mgmt}: a VIP on the
 * SAME bare-metal answers ({@code mgmt-vip.bioskop:6443} OPEN — the packet never leaves the host,
 * so no tailnet filter sees it), while the VIP of a cluster on ANOTHER bare-metal does not, even
 * though the very same address answers from {@code bioskop-nixos} and from the operator's Mac. So
 * the failure is not routing — every hop has a route — it is IDENTITY: a k8s node is an Incus
 * container, not a tailnet device, so the packet arrives bearing an address the policy names
 * nowhere (the fleet holds a no-NAT invariant, so the source survives end to end).
 *
 * <p>An egress {@code ProxyGroup} is the identity-bearing relay: its pods ARE tailnet devices, they
 * carry {@code tag:k8s}, and a grant can then admit them by TAG rather than by address. That is the
 * k8s-native shape — a CR, HA by {@code replicas}, and no policy widened to whole CIDRs.
 *
 * <p>This CR alone is inert: traffic reaches the relay only through an egress {@code Service} of
 * {@code type: ExternalName} annotated with {@code tailscale.com/proxy-group} plus one of {@code
 * tailscale.com/tailnet-ip} / {@code tailscale.com/tailnet-fqdn} (read off the operator source at
 * the version we deploy, v1.102.3 — {@code egress-services.go}; note it is {@code tailnet-ip}, NOT
 * the {@code tailnet-target-ip} of the pre-ProxyGroup form, and an unrecognised annotation is
 * ignored in silence). Those Services are per-target and must be owned by the in-cluster
 * controller, never by Flux: the operator REWRITES {@code spec.externalName} to point at the
 * ClusterIP Service it provisions, so a force-apply would make them flap.
 *
 * <p>No explicit Flux dependency is declared: the CRD arrives at runtime with the
 * tailscale-operator HelmChart, and {@code FluxServiceKustomizationPlanner} already maps the {@code
 * tailscale.com} group to the {@code tailscale/tailscale} unit, so it computes the CRD-before-CR
 * edge itself.
 */
public final class TailscaleEgressManifestsUnit extends AbstractManifestsUnit {

  public static final String MANIFEST_UNIT_ID = ManifestDomainCatalog.TAILSCALE + "/egress";

  /** Exploded package dir (relative to the tailscale domain). */
  public static final String OUTPUT_DIR = "egress";

  /**
   * One relay per cluster, so the object name needs no cluster segment — it is unique in its own
   * API server. The tailnet HOSTNAMES it mints do not have that luxury; see {@link
   * #hostnamePrefix}.
   */
  private static final String PROXY_GROUP_NAME = "egress";

  /**
   * The {@code ProxyClass} carrying {@code acceptRoutes}, named for what it does rather than for
   * the group that uses it — an ingress or kube-apiserver group would want the same setting.
   *
   * <p>⚠️ It is not optional plumbing; without it the relay CANNOT reach a subnet-routed address.
   * The operator sets {@code AcceptRoutes: "false"} for every ProxyGroup, explicitly overriding
   * tailscaled's own default of true ({@code proxygroup.go:1198}), and the ONLY lever is a
   * ProxyClass ({@code shouldAcceptRoutes} reads nothing else — {@code sts.go:1166}). Measured
   * 2026-09-28 inside {@code egress-0}: {@code "RouteAll": false} and an empty routing table for
   * the peer's spans, so a dial to the peer's VIP failed even with the tailnet grant reconciled.
   *
   * <p>★ It also corrects a reading of that same measurement. "Its own bare-metal answers, the peer
   * fails" looked like a policy asymmetry; it is not. The local addresses are reached by the pod's
   * ORDINARY routing — its node sits on the vmnet span and the fabric gateway is its next hop —
   * with no tailnet involved at all. Only the peer needs a tailnet route, and an unaccepted route
   * is never installed. So the grant and this setting are two independent preconditions, and the
   * grant alone proves nothing.
   */
  private static final String PROXY_CLASS_NAME = "accept-routes";

  /**
   * Explicit rather than inherited: the operator defaults to 2, and this is the HA the per-cluster
   * {@code Connector} that used to advertise routes never had — a relay that dies with a single pod
   * would fail exactly while a half-born cluster is being debugged.
   */
  private static final int REPLICAS = 2;

  /**
   * Stated, not defaulted, because it is the CONTRACT the tailnet grant is written against: the
   * grant admits {@code tag:k8s} to the vmnet segments. Leaving it implicit would put half the
   * authorization rule in the operator's defaults, where a version bump could move it silently.
   */
  private static final String PROXY_TAG = "tag:k8s";

  private final PackageMetadataProfile packageProfile =
      new PackageMetadataProfile(ManifestDomainCatalog.TAILSCALE, OUTPUT_DIR);

  public TailscaleEgressManifestsUnit() {
    super(MANIFEST_UNIT_ID, List.of(TailscaleManifestsUnit.MANIFEST_UNIT_ID));
  }

  @Override
  public String outputDir() {
    return OUTPUT_DIR;
  }

  @Override
  protected void doSynthesize(final Construct scope, final ManifestsUnitContext context) {
    final String cluster =
        ManifestSynthesisContext.current()
            .bootstrapIdentity()
            .clusterNameOrDefault(DefaultNodeEnvContext.DEFAULT_CLUSTER_NAME);
    // ProxyClass and ProxyGroup are both CLUSTER-scoped (measured on the live CRDs), so neither
    // carries a namespace and the package key spells `default` — the convention this domain's
    // Namespace unit already uses for a cluster-scoped object.
    final ApiObject proxyClass =
        new ApiObject(
            scope,
            "proxyclass-" + PROXY_CLASS_NAME,
            ApiObjectProps.builder()
                .apiVersion("tailscale.com/v1alpha1")
                .kind("ProxyClass")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(PROXY_CLASS_NAME)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "tailscale.com|ProxyClass|default|" + PROXY_CLASS_NAME))
                        .build())
                .build());
    // ⚠️ `tailscale`, not `tailscaleConfig`: the Go field is `TailscaleConfig` but its JSON tag is
    // `tailscale` (types_proxyclass.go:73), and the CRD only serves the latter — reading the Go
    // name
    // would have produced a key the API server drops in silence.
    proxyClass.addJsonPatch(
        org.cdk8s.JsonPatch.add("/spec", Map.of("tailscale", Map.of("acceptRoutes", true))));

    final ApiObject proxyGroup =
        new ApiObject(
            scope,
            "proxygroup-" + PROXY_GROUP_NAME,
            ApiObjectProps.builder()
                .apiVersion("tailscale.com/v1alpha1")
                .kind("ProxyGroup")
                .metadata(
                    ApiObjectMetadata.builder()
                        .name(PROXY_GROUP_NAME)
                        .annotations(
                            packageProfile.packageAnnotations(
                                "tailscale.com|ProxyGroup|default|" + PROXY_GROUP_NAME))
                        .build())
                .build());
    proxyGroup.addJsonPatch(
        org.cdk8s.JsonPatch.add(
            "/spec",
            Map.of(
                "type",
                "egress",
                "replicas",
                REPLICAS,
                "tags",
                List.of(PROXY_TAG),
                "hostnamePrefix",
                hostnamePrefix(cluster),
                "proxyClass",
                PROXY_CLASS_NAME)));
    proxyGroup.addDependency(proxyClass);
  }

  /**
   * A tailnet hostname is unique across the WHOLE tailnet, so the prefix carries the cluster: two
   * clusters each running a {@code ProxyGroup} named {@code egress} would otherwise both claim
   * {@code ts-egress-0}. That is the same collision already paid on the {@code NodeImage} object
   * name, where a per-cluster reference reused one artefact's alias and the exploder collapsed two
   * files into one.
   */
  private static String hostnamePrefix(final String cluster) {
    return cluster + "-egress-";
  }
}
