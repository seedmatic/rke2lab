package io.seedmatic.rke2lab.maven.staging;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Which {@code type=dual-realm} carriers the flat/host realm of THIS exec-assembly actually
 * references — the switch that decides, per assembly, whether a dual-realm carrier's flat copy is
 * materialised (kept flat AND staged) or folded OSGi-only (staged as a plain bundle).
 *
 * <p>This SUPERSEDES the former {@code DUAL_REALM_JUSTIFIED} build gate. That gate errored when a
 * dual-realm carrier was kept flat with no flat consumer — but "consumed flat" is a property of the
 * ({@code carrier}, {@code exec-assembly}) pair, not of the carrier alone: {@code
 * manifests-ingress-contract} is consumed flat by {@code manifests-cli} (the version bumper) yet
 * only OSGi-side by {@code seed-outcluster}. A per-carrier gate on a per-consumer fact fires in the
 * wrong assembly, and a carrier cannot know at its own build time which future host applications
 * will consume it flat. So instead of ERRORING on an unused flat copy, the staging strategy simply
 * does not materialise it: the flat copy exists IFF a flat class references the carrier, by
 * construction — nothing left for a gate to catch.
 *
 * <p>The count is a STATIC bytecode reference count (via {@link ReferencedTypes}), so it sees
 * UNEXERCISED references a booted test could not — the same reason {@code REALM_BOUNDARY} stays
 * static. A carrier's OWN classes are skipped: a package references itself trivially, which is not
 * an outside flat consumer. A flat consumer reachable only by reflection (a string class name) is
 * not seen and folds OSGi-only — the same limitation the former gate carried.
 */
final class DualRealmFlatDemand {

  private DualRealmFlatDemand() {}

  /**
   * The {@code groupId:artifactId} of every dual-realm carrier in {@code dualRealms} that at least
   * one class in {@code flatRealmClasses} (outside the carrier's own exported packages) references.
   */
  static Set<String> flatReferencedGas(
      List<ResolvedBundle> dualRealms, List<ResolvedBundle.ClassEntry> flatRealmClasses) {
    final Set<String> referenced = new LinkedHashSet<>();
    for (ResolvedBundle carrier : dualRealms) {
      final Set<String> exported = carrier.ourExportedPackages();
      if (exported.isEmpty()) {
        continue; // exports nothing of ours — the flat-vs-OSGi split question does not arise.
      }
      if (anyFlatClassReferences(exported, flatRealmClasses)) {
        referenced.add(carrier.ga());
      }
    }
    return referenced;
  }

  private static boolean anyFlatClassReferences(
      Set<String> exported, List<ResolvedBundle.ClassEntry> flatRealmClasses) {
    for (ResolvedBundle.ClassEntry entry : flatRealmClasses) {
      if (exported.contains(packageOf(entry.binaryName()))) {
        continue; // the carrier's own class — not an outside flat consumer.
      }
      for (String referenced : ReferencedTypes.in(entry.bytes())) {
        if (exported.contains(referenced)) {
          return true;
        }
      }
    }
    return false;
  }

  private static String packageOf(String binaryName) {
    final int slash = binaryName.lastIndexOf('/');
    return slash < 0 ? "" : binaryName.substring(0, slash).replace('/', '.');
  }
}
