package io.seedmatic.rke2lab.seed.broker.port;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an INPUT wire-record component as an AMENDMENT — a growth-need the soil (a domain)
 * declares, that the gardener (host) fills from its provisioning state before it sows. The AMONT
 * twin of {@link Scion}: a scion is a reaped part a domain marks by its neutral {@link Role} so the
 * host affixes it without learning the field name; an amendment is a needed input a domain marks by
 * its neutral role so the host FILLS it without learning the field name. They meet on the role —
 * never on the other's field or state name.
 *
 * <p>Its {@link #value} is the amendment's ROLE, drawn from the neutral vocabulary held here as
 * String constants ({@link #SOIL}, {@link #FACET}) — the amont twin of {@link Role} (fruit /
 * sowing), a single source so no call site spells a role as a magic string. String constants (not
 * an enum) so a role is usable as an annotation element ({@code @Amendment(Amendment.SOIL)} — an
 * annotation value must be a constant expression). A domain maps its input components onto these:
 * {@code @Amendment(Amendment.SOIL)} on the materialisation-root component,
 * {@code @Amendment(Amendment.FACET)} on the activation facet; the host maps its state onto the
 * same role ("fill {@code SOIL} with the plot I materialise into"), never the domain's field name.
 *
 * <p>The SHAPE of the payload is projected separately by the {@link ShapeCoordinate} reflector (the
 * JSON Schema of the {@code @SeedContract} wire-record); the schema says the FORM, this annotation
 * says WHICH of the host's states fills each field — the mapping the schema alone cannot carry (a
 * schema knows nothing of the host's provisioning topology). See
 * docs/architecture/osgi/seed-broker-spec.adoc (§ @Amendment) and the gardening lexicon (amendement
 * / amender).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Amendment {

  /** The plot the soil materialises into — the host fills it from its provisioning paths. */
  String SOIL = "soil";

  /** The activation facet the sow carries — the host fills it from the run's policy. */
  String FACET = "facet";

  /**
   * The identity provisioning coordinates — the flat scalars (identity root, cluster, node,
   * automount) the host holds and a domain needs to RECONSTRUCT the provisioning topology in-world.
   * The host fills it from its {@code BootstrapConfig}; the scion computes its own paths from it (§
   * host-cellar-realisation, the whole topology is computed OSGi-side).
   */
  String IDENTITY = "identity";

  /**
   * The seed-image build coordinates — the flat scalars (image alias, builder binary/host, shared
   * artifact folder) the host holds and the incus scion needs to drive the image build and PROJECT
   * the image view the host GROW actualises. The host fills it from its {@code BootstrapConfig};
   * the scion folds the edge's {@code recipeDigest} with these into the {@code buildChecksum} and
   * resolves the artifact paths (§ host-cellar-realisation, the scion-projects/host-actualises
   * rule).
   */
  String IMAGE = "image";

  /**
   * The built image's IDENTITY — the node-base image the artifacts resolve to (alias, content
   * fingerprint, build checksum, incus project/remote, and the baked RKE2 version). Distinct from
   * {@link #IMAGE} (the build COORDINATES the host holds): this is what the incus scion COMPUTES
   * from the freshly-built artifacts (the split-image fingerprint + the emitted {@code
   * rke2.version}) and forwards to the manifests synthesis, which pins the {@code
   * LXCMachineTemplate} image and the {@code RKE2ControlPlane} version from it. Born in the incus
   * crossing (the image exists before any Pulumi resource), so it rides this amendment, not the
   * cellar.
   */
  String IMAGE_STATE = "image-state";

  /**
   * The public funnel endpoint URL — the Tailscale funnel FQDN ({@code
   * https://<leaf>.<tailnet>.ts.net}) a domain must point an external callback at. Only the host
   * holds it: the MagicDNS leaf is a shared manifest constant, but the tailnet suffix is
   * host-config ({@code BootstrapConfig.tailnet}), and Tailscale appends it at runtime — it is
   * never on the in-container synthesis context. The host fills it; the ghapp webhook scion binds
   * it onto the App's hook config.
   */
  String FUNNEL = "funnel";

  /**
   * How the render resolves its facet against the branch HEAD — the CLI verb intent (seeded wins /
   * HEAD wins / HEAD overlaid). The sower fills it; the manifests synthesis reads it. Unamended, it
   * falls to the manifests scion's default (grow: the seeded facet wins), so seed-master's grow is
   * unchanged and only the {@code manifests-cli} update/edit verbs opt into following HEAD.
   */
  String RENDER_MODE = "render-mode";

  /**
   * The workload clusters this grow must pre-seed a deterministic CA for — the flat list of CAPI
   * {@code Cluster} names ({@code <host>-<role>}, e.g. {@code bioskop-wrkld}) the management
   * cluster will greenfield. Only the host holds it (it is the same {@code workloadTargets} the
   * manifests facet carries, read from {@code BootstrapConfig}); the cluster-pki seal scion fills
   * it to mint — additively, once per cluster, rooted at {@code mammoth-skate} (sibling of the mgmt
   * CA) — the four CAPRKE2 BYO-CA sets ({@code <cluster>-{ca,cca,etcd,peer-etcd}}). Empty on a
   * mgmt-only run.
   */
  String WORKLOAD_TARGETS = "workload-targets";

  /** The neutral gardening role of this amendment (e.g. {@link #SOIL}, {@link #FACET}). */
  String value();
}
