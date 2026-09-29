---
name: osgi-package-versioning-carto
description: "DESIGN/CARTO (read-only on integration @3ee432a6, 2026-06-19): OSGi package versioning across all of osgi/. The user's principle, generalised from the contract discussion: in OSGi each EXPORTED PACKAGE carries its OWN semver, independent of the reactor build version — not just the contracts, ALL osgi/ bundles. AUDIT FINDING: today ZERO package-info @Version exists; bnd mechanically stamps version=\"0.1.0\" (the reactor Maven version) onto every exported package, so all public API is glued to the build version and never evolves independently; NO bnd baseline is configured (semver is neither authored nor enforced). TARGET: author per-package @Version via package-info.java (decoupled from the 0.1.0-SNAPSHOT GAV), turn on bnd's baseline check so breaking/additive changes force the right semver bump. Two levels distinguished: per-EXPORTED-PACKAGE @Version (the idiom core, orthogonal to Maven GAV — DO THIS) vs per-Bundle-Version decoupled from the reactor (heavier, GAV divergence — deferred). The contract slice ([[contract-placement-and-versioning-carto]]) is the FIRST application; this note generalises the rule to every osgi/ exported package. NOT coded."
metadata:
  node_type: memory
  type: project
---

## The principle (user, 2026-06-19) — generalised from the contract discussion

The contract-versioning decision (each contract versions independently, [[contract-placement-and-versioning-carto]])
is a SPECIAL CASE of a general OSGi truth the user named: *"en OSGi on versionne par rapport aux
packages eux-mêmes, chaque bundle porte sa propre version."* It applies to ALL of osgi/, not only the
contracts — every exported package is an API surface with its own semver, decoupled from the reactor
build version.

## Audit — the current reality (integration @3ee432a6, generated manifests)

1. **No authored package versioning.** ZERO `package-info.java` with `@org.osgi.annotation.versioning
   .Version` anywhere in osgi/. The semver is not authored at the source — it does not exist as intent.
2. **bnd stamps the reactor version onto every export.** Every exported package comes out
   `version="0.1.0"` — bnd's default when no package version is declared is to inherit the bundle
   (Maven) version. So `manifests`, `netplan`, `unitrepo.core`, `unitrepo.handler`, `systemdcontract.api`,
   `cdk8s.systemd` are ALL `0.1.0`, in lockstep with the `0.1.0-SNAPSHOT` reactor. They cannot move
   independently — bump the reactor and every package "version" bumps together, meaninglessly.
3. **No baseline check.** No `-baseline`/baselining configured in build-parent or any bnd.bnd. So even
   if versions were authored, nothing ENFORCES semver (bnd-baseline-maven-plugin compares against the
   last released jar and FAILS the build if a breaking change didn't bump major / an addition didn't
   bump minor). Today semver is neither authored nor checked.

So the project is at "OSGi bundles by mechanism, but versioned like one monolithic reactor" — the
anti-idiom. The packaging is OSGi; the versioning discipline is not yet.

## The two levels (distinguish — they differ in cost)

- **Per-EXPORTED-PACKAGE `@Version` (the idiom core — DO THIS).** Each exported package declares its own
  semver in `package-info.java` (`@Version("1.2.0")`). This is what makes versioned IMPORTS meaningful
  (consumers import `pkg;version="[1.2,2)"`) and what bnd's baseline check operates on. It is ORTHOGONAL
  to the Maven GAV — the module stays `0.1.0-SNAPSHOT` in Maven, while its exported packages carry their
  own API semver. Low friction, high idiom value. THE target.
- **Per-Bundle-Version decoupled from the reactor (heavier — DEFER).** Giving each bundle its own
  `Bundle-Version` distinct from the Maven `<version>` introduces a GAV-vs-OSGi divergence to manage (a
  Maven module has ONE `<version>`; the release mechanics would need to source Bundle-Version elsewhere).
  Not required for the idiom and not worth the friction now. The package-level versioning above delivers
  the actual benefit (independent API evolution + semver enforcement) without it.

## Target shape (to plan as its own transverse slice, after the contract slice)

1. **Author `package-info.java @Version` on every EXPORTED package** in osgi/ (the `Export-Package`
   sets seen in the audit). Private/glue packages need none (they are not exported). Start each at a
   considered version — most at `1.0.0` (first published API), unless a package is known unstable.
   Each package versions INDEPENDENTLY thereafter.
2. **Turn on `bnd-baseline-maven-plugin`** (or the `-baseline` instruction) in `osgi/bundle-parent` so
   every bundle is checked against its last release: a breaking export change without a major bump
   FAILS the build; an addition without a minor bump WARNS/FAILS. This is the machine-enforcement that
   makes per-package semver real rather than decorative — sibling discipline to the `@NonNullByDefault`
   + `-Werror` items in [[java-cleanup-backlog]].
3. **Keep the Maven GAV at `0.1.0-SNAPSHOT`** (reactor) — versioning is at the package layer, not the
   GAV. (Per-Bundle-Version decoupling deferred per above.)

## ★ SHIPPED 2026-06-19 (squash merge dfdd1d23)

Done in the osgi-cleanup slice ([[osgi-cleanup-slice-state]]) — NOT as a separate later slice, it was
folded in with the port re-placement. `@Version("1.0.0")` graved on every exported osgi/ package (the
audit's Export-Package sets), decoupled from the 0.1.0-SNAPSHOT GAV. `bnd-baseline-maven-plugin` wired
in `osgi/bundle-parent` with `failOnMissing=false` — the "decisions owed" below were all resolved:
the deliberate-install model answers "what released jar to compare against" ([[osgi-baseline-install-discipline]]:
a release coordinate ≠ SNAPSHOT, frozen by a deliberate rare act, consumed by bnd-baseline ONLY, never
from ~/.m2 as a build dep). Per-Bundle-Version decoupling stayed deferred as planned.

## Sequencing (historical — pre-merge)

- The CONTRACT slice ([[contract-placement-and-versioning-carto]]) is the FIRST application: when the
  contracts are bundle-ified in osgi/, they get `package-info @Version` from the start (each independent,
  start 1.0.0). Do that there.
- This transverse note is the rule for the REST of osgi/ (the impl bundles, unitrepo, systemd, the bench
  is disposable so skip) — a SEPARATE slice after the contract one, paired with turning on baselining.
  Sequence it relative to R4 with the user (it is hygiene, not on the R4 critical path).
- Decisions owed before that slice: per-package starting versions (mostly 1.0.0?); baseline plugin vs
  raw `-baseline`; what "released jar" the baseline compares against in a SNAPSHOT-only project (likely
  the previous reactor build / a pinned baseline repo) — needs a short carto of its own.

See [[contract-placement-and-versioning-carto]] (the first application — independent per-contract
version), [[api-extraction-tri-carto-state]] (the contract sort), [[java-cleanup-backlog]] (baselining
joins -Werror / @NonNullByDefault as machine-enforced hygiene), [[bnd-annotations-spike-state]] (bnd is
already the source of truth for headers; versioning extends that), the atlas §"two spaces".
