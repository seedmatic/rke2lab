package io.seedmatic.rke2lab.manifests.units.clusterapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.manifests.Cdk8sApiObjectResolver;
import io.seedmatic.rke2lab.manifests.ManifestSynthesisContext;
import io.seedmatic.rke2lab.manifests.ManifestsUnit;
import io.seedmatic.rke2lab.manifests.ManifestsUnitContext;
import io.seedmatic.rke2lab.manifests.YamlMapper;
import io.seedmatic.rke2lab.manifests.contract.ClusterFleet;
import io.seedmatic.rke2lab.manifests.contract.ManifestDomainPolicy;
import io.seedmatic.rke2lab.manifests.contract.ManifestSynthesisRequest;
import io.seedmatic.rke2lab.manifests.contract.profiles.BootstrapIdentity;
import io.seedmatic.rke2lab.manifests.contract.profiles.ImageState;
import io.seedmatic.rke2lab.manifests.contract.profiles.NodeRuntime;
import io.seedmatic.rke2lab.manifests.node.DefaultNodeEnvContext;
import io.seedmatic.rke2lab.manifests.units.cluster.ClusterRuntimeNamespaceManifestsUnit;
import io.seedmatic.rke2lab.manifests.units.runtime.SeedInclusterManifestsUnit;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.cdk8s.App;
import org.cdk8s.AppProps;
import org.cdk8s.Chart;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The cluster-api units RENDERED with a bound {@link ImageState}, which is the only condition under
 * which they emit anything at all.
 *
 * <p>Why this exists: without a bound image state those units NO-OP, so every test passed while the
 * render was broken and the first real check was a grow — three defects in three cold starts on
 * 2026-09-28, each minutes into a provisioning run:
 *
 * <ul>
 *   <li>a cdk8s construct-id collision, because every workload target rendered into ONE scope and
 *       the id was derived from the image alias, which is fleet-wide;
 *   <li>a CR rendered whose CRD the seed-incluster unit did not emit, so the synthesis'
 *       CRD-provider gate refused the render;
 *   <li>a pool referencing the image the wrong way round.
 * </ul>
 *
 * <p>Same bargain as {@link io.seedmatic.rke2lab.manifests.unitrepo.RealDomainGraphTest}: seconds
 * here instead of minutes there, against the REAL units rather than a fixture.
 *
 * <p>TWO workload targets, deliberately: one target cannot collide with itself, so a single-target
 * fixture would have reproduced none of it.
 */
@Tag("osgi")
class ClusterApiRenderTest {

  private static final String MANAGEMENT_CLUSTER = "bioskop-mgmt";

  /**
   * The live fleet. The children this render emits are DERIVED from it by the owner rule — {@code
   * bioskop-wrkld} (host-local) and {@code nikopol-mgmt} (the one cross-host hop) — which is
   * exactly the pair {@code manifests/bioskop-mgmt} carries today.
   */
  private static final ClusterFleet FLEET =
      new ClusterFleet(List.of("bioskop", "nikopol"), "bioskop");

  /** The operator's host declaration the render cannot derive — see NodeDeviceSet. */
  private static final String FABRIC_BRIDGE = "fabric-br";

  /** Only the domains under test — a policy is required, and an absent one renders nothing. */
  private static final ManifestDomainPolicy POLICY =
      ManifestDomainPolicy.builder().clusterApi(true).runtime(true).cluster(true).build();

  /** Our CR group — the one whose CRDs this project renders itself. */
  private static final String OWNED_GROUP = "cluster.seedmatic.io";

  /**
   * A realised image, shaped exactly as the incus scion forwards it. The runtime contract is
   * present because {@link ImageState} requires it — a recording without it is undecodable, which
   * is the point of that requirement.
   */
  private static ImageState imageState() {
    return new ImageState(
        "node-base",
        "0f7a1c9d2b3e4f5a6b7c8d9e0f1a2b3c4d5e6f708192a3b4c5d6e7f809a1b2c3",
        "sha256-buildinputs",
        "rke2lab",
        "https://nixos.bioskop:8443",
        "1.34.1+rke2r1",
        new NodeRuntime(
            List.of("ip_vs", "xfrm_user", "nft_compat"),
            "lxc.mount.auto = proc:rw sys:rw cgroup:rw",
            true,
            true,
            true,
            true));
  }

  private static BootstrapIdentity identity() {
    return new BootstrapIdentity(
        MANAGEMENT_CLUSTER,
        1,
        "token",
        "cluster.local",
        "dev",
        "master",
        1,
        "management",
        "bioskop-nixos",
        "bioskop-mgmt-master",
        "bioskop-mgmt-master.bioskop");
  }

  /**
   * Render the given units into ONE chart, as a synthesis does, and return the emitted documents.
   */
  private static List<Map<String, Object>> render(
      final Path outdir, final List<ManifestsUnit> units) {
    final App app = new App(AppProps.builder().outdir(outdir.toString()).build());
    final Chart chart = new Chart(app, "manifests");
    final ManifestSynthesisRequest request =
        ManifestSynthesisRequest.builder(outdir, outdir.resolve("manifests.yaml"))
            .bootstrapIdentity(identity())
            .imageState(Optional.of(imageState()))
            .clusterFleet(Optional.of(FLEET))
            .manifestDomainPolicy(Optional.of(POLICY))
            .fabricBridgeParent(Optional.of(FABRIC_BRIDGE))
            .build();

    try (var bound = ManifestSynthesisContext.of(request).bind()) {
      for (final ManifestsUnit unit : units) {
        unit.apply(
            new ManifestsUnitContext(
                chart,
                "cluster-api",
                unit.manifestUnitId(),
                new Cdk8sApiObjectResolver(chart),
                POLICY,
                new DefaultNodeEnvContext(identity()),
                new YamlMapper()));
      }
    }

    final List<Map<String, Object>> documents = new ArrayList<>();
    for (final Object document : chart.toJson()) {
      @SuppressWarnings("unchecked")
      final Map<String, Object> asMap = (Map<String, Object>) document;
      documents.add(asMap);
    }
    return documents;
  }

  private static List<ManifestsUnit> clusterApiUnits() {
    final ClusterApiCrRenderer renderer = new ClusterApiCrRenderer();
    return List.of(
        new ClusterApiManagementManifestsUnit(renderer),
        new ClusterApiWorkloadManifestsUnit(renderer));
  }

  private static String kindOf(final Map<String, Object> document) {
    return String.valueOf(document.get("kind"));
  }

  private static String groupOf(final Map<String, Object> document) {
    final String apiVersion = String.valueOf(document.get("apiVersion"));
    final int slash = apiVersion.indexOf('/');
    return slash < 0 ? "" : apiVersion.substring(0, slash);
  }

  @Test
  void requireEveryClusterGetsItsOwnNodeImage(@TempDir Path outdir) {
    final List<Map<String, Object>> documents = render(outdir, clusterApiUnits());

    // Names must be DISTINCT, not just namespaces: the exploder writes one file per kind + name.
    final List<String> names =
        documents.stream()
            .filter(d -> "NodeImage".equals(kindOf(d)))
            .map(d -> (Map<String, Object>) d.get("metadata"))
            .map(m -> String.valueOf(m.get("name")))
            .sorted()
            .toList();
    // The WHOLE fleet, not just what this plane adopts: the federation view is UNIFORM, and a plane
    // decides what to ACT on from `adoptedBy` rather than from what it was handed.
    assertEquals(
        List.of(
            "bioskop-mgmt-node-base",
            "bioskop-wrkld-node-base",
            "nikopol-mgmt-node-base",
            "nikopol-wrkld-node-base"),
        names);

    final List<String> namespaces =
        documents.stream()
            .filter(d -> "NodeImage".equals(kindOf(d)))
            .map(d -> (Map<String, Object>) d.get("metadata"))
            .map(m -> String.valueOf(m.get("namespace")))
            .toList();

    // One per DECLARED cluster — four, the whole fleet. The metadata NAME repeats (it is the
    // fleet-wide image alias, scoped by namespace); only the construct id is per-cluster, and
    // deriving it from the alias instead collided on the second target.
    assertEquals(
        List.of(
            "rke2lab-bioskop-mgmt",
            "rke2lab-bioskop-wrkld",
            "rke2lab-nikopol-mgmt",
            "rke2lab-nikopol-wrkld"),
        namespaces.stream().sorted().toList());
  }

  @Test
  void requireTheDeclarationIsUniformButTheMaterialFollowsTheAdopter(@TempDir Path outdir) {
    final List<Map<String, Object>> documents = render(outdir, clusterApiUnits());

    // The DECLARATION reaches every declared cluster — the uniform federation view.
    final List<String> declared =
        documents.stream()
            .filter(d -> "ClusterIntention".equals(kindOf(d)))
            .map(d -> (Map<String, Object>) d.get("spec"))
            .map(spec -> String.valueOf(spec.get("clusterName")))
            .sorted()
            .toList();
    assertEquals(
        List.of("bioskop-mgmt", "bioskop-wrkld", "nikopol-mgmt", "nikopol-wrkld"), declared);

    // ★ But CREDENTIALS do not. The render subject here is bioskop-mgmt, which adopts itself,
    // bioskop-wrkld and nikopol-mgmt — NOT nikopol-wrkld, whose adopter is nikopol-mgmt. So no CA
    // Secret may appear for it: carrying credentials where a plane has no business acting is
    // exactly
    // how a child's branch came to hold its parent's CA and then its parent's admin certificate.
    final List<String> caNamespaces =
        documents.stream()
            .filter(d -> "Secret".equals(kindOf(d)))
            .map(d -> (Map<String, Object>) d.get("metadata"))
            .map(m -> String.valueOf(m.get("namespace")))
            .distinct()
            .sorted()
            .toList();
    assertFalse(
        caNamespaces.contains("rke2lab-nikopol-wrkld"),
        "bioskop-mgmt must carry NO material for nikopol-wrkld — its adopter is nikopol-mgmt, but it"
            + " still carries its DECLARATION: "
            + caNamespaces);
  }

  @Test
  void requirePoolIntentionsReferenceTheNodeImageByName(@TempDir Path outdir) {
    final List<Map<String, Object>> documents = render(outdir, clusterApiUnits());

    final List<Map<String, Object>> pools =
        documents.stream().filter(d -> "PoolIntention".equals(kindOf(d))).toList();
    // One per declared cluster: the declaration is uniform across the fleet.
    assertEquals(4, pools.size());

    for (final Map<String, Object> pool : pools) {
      @SuppressWarnings("unchecked")
      final Map<String, Object> spec = (Map<String, Object>) pool.get("spec");
      @SuppressWarnings("unchecked")
      final Map<String, Object> image = (Map<String, Object>) spec.get("image");
      // A REFERENCE, not an embedded fingerprint: the pool names the NodeImage that describes the
      // artifact, so the controller derives the image pin AND the runtime contract from one object.
      assertEquals(Set.of("name"), image.keySet());
      // Cluster-SCOPED: every target renders into one cell and the exploder names files by object
      // name, so a fleet-wide `node-base` would collapse two NodeImages onto one file — which it
      // did,
      // leaving bioskop-wrkld with none and its pool at ImageMissing.
      assertEquals(String.valueOf(spec.get("clusterRef")) + "-node-base", image.get("name"));
    }
  }

  @Test
  void requirePoolIntentionsCarryTheFullDeviceSet(@TempDir Path outdir) {
    final List<Map<String, Object>> documents = render(outdir, clusterApiUnits());

    for (final Map<String, Object> pool :
        documents.stream().filter(d -> "PoolIntention".equals(kindOf(d))).toList()) {
      @SuppressWarnings("unchecked")
      final Map<String, Object> spec = (Map<String, Object>) pool.get("spec");
      @SuppressWarnings("unchecked")
      final List<String> devices = (List<String>) spec.get("devices");
      final String cluster = String.valueOf(spec.get("clusterRef"));

      // ⚠️ `root` above all: with no profile named, NOTHING else supplies a disk — Incus's own
      // `default` profile is not applied when the profile list is empty, and ours is what was
      // removed. A node born without it is the failure this asserts against.
      assertTrue(
          devices.stream().anyMatch(d -> d.startsWith("root,type=disk")),
          () -> cluster + " has no root disk device: " + devices);
      // The fabric bridge is the operator's declaration, the vmnet one follows the cluster's role.
      assertTrue(
          devices.contains("fabric0,type=nic,name=fabric0,nictype=bridged,parent=" + FABRIC_BRIDGE),
          () -> cluster + " is not attached to " + FABRIC_BRIDGE + ": " + devices);
      final String role = cluster.substring(cluster.lastIndexOf('-') + 1);
      assertTrue(
          devices.contains("vmnet0,type=nic,name=vmnet0,nictype=bridged,parent=vmnet-" + role),
          () -> cluster + " is not on vmnet-" + role + ": " + devices);
      // No profile is named any more: the devices ARE the declaration.
      assertFalse(spec.containsKey("profiles"), () -> cluster + " still names Incus profiles");
    }
  }

  @Test
  void requireEveryOwnedCrHasItsCrdRendered(@TempDir Path outdir) {
    final List<ManifestsUnit> units = new ArrayList<>(clusterApiUnits());
    // The seed-incluster unit REFERENCES the rke2lab-system Namespace another unit creates, so the
    // prerequisite is rendered too — the resolver is a real cross-unit lookup, not a stub.
    units.add(new ClusterRuntimeNamespaceManifestsUnit());
    units.add(new SeedInclusterManifestsUnit());
    final List<Map<String, Object>> documents = render(outdir, units);

    final Set<String> renderedCrKinds = new LinkedHashSet<>();
    final Set<String> crdKinds = new LinkedHashSet<>();
    for (final Map<String, Object> document : documents) {
      if ("CustomResourceDefinition".equals(kindOf(document))) {
        @SuppressWarnings("unchecked")
        final Map<String, Object> spec = (Map<String, Object>) document.get("spec");
        if (OWNED_GROUP.equals(String.valueOf(spec.get("group")))) {
          @SuppressWarnings("unchecked")
          final Map<String, Object> names = (Map<String, Object>) spec.get("names");
          crdKinds.add(String.valueOf(names.get("kind")));
        }
      } else if (OWNED_GROUP.equals(groupOf(document))) {
        renderedCrKinds.add(kindOf(document));
      }
    }

    // A CR whose CRD nothing provides fails the render at the CRD-provider gate — correctly,
    // because
    // the Flux planner cannot compute the ordering edge that keeps Flux from applying the CR before
    // its CRD. Adding a kind without adding its staged CRD to the seed-incluster unit is exactly
    // the
    // omission this catches.
    assertFalse(renderedCrKinds.isEmpty(), "the cluster-api units rendered no owned CR");
    assertTrue(
        crdKinds.containsAll(renderedCrKinds),
        () ->
            "rendered CRs with no CRD emitted: "
                + renderedCrKinds.stream().filter(kind -> !crdKinds.contains(kind)).toList());
  }
}
