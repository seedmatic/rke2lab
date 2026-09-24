package io.seedmatic.rke2lab.maven.staging;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.seedmatic.rke2lab.osgi.bnd.EmbedCapability;
import io.seedmatic.rke2lab.osgi.bnd.OsgiHeader;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StagingClosureTest {

  private static ResolvedBundle bundle(
      String g, String a, String type, String imports, String exports) {
    return new ResolvedBundle(
        g,
        a,
        "1",
        Optional.empty(),
        Optional.of(g + "." + a),
        Optional.ofNullable(
            EmbedCapability.of(OsgiHeader.parse("io.seedmatic.rke2lab.embed;type=" + type))),
        OsgiHeader.parse(imports),
        OsgiHeader.parse(exports),
        false,
        false);
  }

  private static ResolvedBundle thirdParty(String g, String a, String exports) {
    return new ResolvedBundle(
        g,
        a,
        "1",
        Optional.empty(),
        Optional.of(g + "." + a),
        Optional.empty(),
        OsgiHeader.parse(null),
        OsgiHeader.parse(exports),
        false,
        false);
  }

  /** A third-party bundle the exec-module declares as a DIRECT dependency (a root graph child). */
  private static ResolvedBundle directThirdParty(String g, String a, String exports) {
    return thirdParty(g, a, exports).asDirectlyDeclared();
  }

  /** A third-party bundle carrying an explicit symbolic name (e.g. a boot-stack member). */
  private static ResolvedBundle named(String g, String a, String symbolicName, String exports) {
    return new ResolvedBundle(
        g,
        a,
        "1",
        Optional.empty(),
        Optional.of(symbolicName),
        Optional.empty(),
        OsgiHeader.parse(null),
        OsgiHeader.parse(exports),
        false,
        false);
  }

  @Test
  void aThirdPartyBundleAModelImportsIsStagedAsRealmLibrary() {
    // doctor-core (model) imports com.fasterxml.jackson.databind; jackson-databind (third-party
    // OSGi bundle) exports it. The realm library MUST be staged (its own OSGi copy) even though the
    // host serves it flat too.
    final ResolvedBundle model =
        bundle(
            "io.seedmatic.rke2lab",
            "doctor-core",
            "model",
            /*imports*/ "com.fasterxml.jackson.databind",
            /*exports*/ "io.seedmatic.rke2lab.doctor");
    final ResolvedBundle jackson =
        thirdParty(
            "com.fasterxml.jackson.core",
            "jackson-databind",
            /*exports*/ "com.fasterxml.jackson.databind");

    final StagingClosure closure = StagingClosure.compute(List.of(model, jackson), Set.of());

    assertTrue(
        closure.stagedGas().contains("com.fasterxml.jackson.core:jackson-databind"),
        "the realm library is staged as a bundle");
    assertTrue(
        closure.realmLibraryGas().contains("com.fasterxml.jackson.core:jackson-databind"),
        "it is classified a realm library (flat AND staged)");
  }

  @Test
  void aRealmLibraryIsNotStagedWhenTheBootStackAlreadyProvidesItsPackage() {
    // manifests-core (model) imports org.slf4j; slf4j-api (third-party) exports it — but
    // pax-logging-api (boot-stack) ALSO exports org.slf4j in-framework. Staging slf4j-api would add
    // a second in-framework exporter and break slf4j 2.x resolution, so it must NOT be a realm
    // library; it stays host-flat only.
    final ResolvedBundle model =
        bundle(
            "io.seedmatic.rke2lab",
            "manifests-core",
            "model",
            /*imports*/ "org.slf4j",
            /*exports*/ "io.seedmatic.rke2lab.manifests");
    final ResolvedBundle slf4j = thirdParty("org.slf4j", "slf4j-api", /*exports*/ "org.slf4j");
    final ResolvedBundle pax =
        named(
            "org.ops4j.pax.logging",
            "pax-logging-api",
            "org.ops4j.pax.logging.pax-logging-api",
            /*exports*/ "org.slf4j");

    final StagingClosure closure = StagingClosure.compute(List.of(model, slf4j, pax), Set.of());

    assertTrue(
        !closure.realmLibraryGas().contains("org.slf4j:slf4j-api"),
        "slf4j-api is not a realm library — pax (boot-stack) already provides org.slf4j");
    assertTrue(
        !closure.stagedGas().contains("org.slf4j:slf4j-api"),
        "slf4j-api is not staged — staging it would add a second org.slf4j exporter");
  }

  @Test
  void aDirectlyDeclaredThirdPartyIsARealmLibraryEvenWhenNoDomainImportsIt() {
    // gson: seed-master declares it a DIRECT compile dep (com.pulumi needs it host-flat at
    // startup), and an edge's client pulls it into the staging closure — but no domain
    // Import-Package: com.google.gson, so the import-signal alone would exclude it from flat. The
    // direct declaration IS the developer's keep-flat intent, so gson must be staged AND flat.
    final ResolvedBundle edge =
        bundle(
            "io.seedmatic.rke2lab",
            "incus-edge",
            "edge",
            /*imports*/ "io.seedmatic.rke2lab.incus.contract",
            /*exports*/ "io.seedmatic.rke2lab.incus.edge");
    final ResolvedBundle gson =
        directThirdParty("com.google.code.gson", "gson", /*exports*/ "com.google.gson");

    final StagingClosure closure = StagingClosure.compute(List.of(edge, gson), Set.of());

    assertTrue(
        closure.realmLibraryGas().contains("com.google.code.gson:gson"),
        "a directly-declared third-party bundle is a realm library (flat AND staged)");
    assertTrue(
        !closure.shadeExcludeGas().contains("com.google.code.gson:gson"),
        "so it is NOT excluded from the flat uber-jar — com.pulumi finds it at startup");
  }

  @Test
  void aDirectlyDeclaredThirdPartyIsNotARealmLibraryWhenTheBootStackServesItsPackage() {
    // Even a DIRECT declaration cannot make a bundle a realm library when the boot-stack already
    // exports its package in-framework — a second exporter would break resolution (slf4j 2.x). The
    // boot-stack guard trumps the direct-declaration signal.
    final ResolvedBundle slf4j =
        directThirdParty("org.slf4j", "slf4j-api", /*exports*/ "org.slf4j");
    final ResolvedBundle pax =
        named(
            "org.ops4j.pax.logging",
            "pax-logging-api",
            "org.ops4j.pax.logging.pax-logging-api",
            /*exports*/ "org.slf4j");

    final StagingClosure closure = StagingClosure.compute(List.of(slf4j, pax), Set.of());

    assertTrue(
        !closure.realmLibraryGas().contains("org.slf4j:slf4j-api"),
        "the boot-stack guard trumps direct declaration — slf4j-api stays host-flat only");
  }

  @Test
  void aSeamPackageIsNotStagedEvenIfAModelImportsIt() {
    final ResolvedBundle model =
        bundle(
            "io.seedmatic.rke2lab",
            "doctor-core",
            "model",
            "io.seedmatic.rke2lab.seed.broker.port",
            "io.seedmatic.rke2lab.doctor");
    final ResolvedBundle seam =
        bundle(
            "io.seedmatic.rke2lab",
            "seed-broker-port",
            "seam",
            null,
            "io.seedmatic.rke2lab.seed.broker.port");
    final StagingClosure closure = StagingClosure.compute(List.of(model, seam), Set.of());
    assertTrue(closure.realmLibraryGas().isEmpty(), "a seam is host-flat, never a realm library");
  }
}
