---
name: osgi-system-export-resolution-only
description: "INVARIANT (user-required, 2026-06-19, R4): a package exported via system.packages.extra provides TYPE RESOLUTION ONLY — it carries NONE of the OSGi behaviour (SCR activation, Provide-Capability, metatype, lifecycle) of its origin bundle, because a system export discards the bundle manifest. Rule: a module may go to system.packages.extra ONLY if it is PASSIVE (pure types); a module with active OSGi behaviour MUST be installed as an intact bundle or its features go silently inert. Single-exporter discipline (R1): exactly one exporter per package, else split-package → two class copies → typed lookup misses."
metadata:
  node_type: memory
  type: project
---

## The invariant (the user asked for this to be documented, not just implied)

When R4 boots Felix inside seed-master and feeds it `org.osgi.framework.system.packages.extra`, every
package listed there is served by the **system bundle** off the host's flat classpath. That gives the
installed bundles **type resolution only** — the shared class — and nothing else. A `system.packages.extra`
export does NOT carry:

- SCR activation (`@Component` → published service),
- `Provide-Capability` (e.g. `osgi.service`, extender capabilities),
- metatype / Config Admin descriptors,
- bundle lifecycle (start/stop, BundleActivator).

All of that lives in the **bundle manifest + OSGI-INF**, which a flat system export throws away.

## The rule it forces — criterion is DESIGNED-FOR-OSGi (user, 2026-06-19)

The discriminator is NOT "is it passive today" (that needs an audit and breaks the day a `-core` gains a
`@Component`). The user sharpened it: **`system.packages.extra` ⟺ "not designed for OSGi".** A jar that
was never conceived as a bundle (no OSGi manifest, e.g. the jsii-generated `org.cdk8s` /
`software.constructs`) belongs flat on the system bundle — that is its *natural* place, not a compromise.
Symmetrically, anything **designed for OSGi** — our `-core`/`-port` bundles AND third-party libs that are
already real bundles (versioned Import-Package: jackson, snakeyaml, slf4j, commons-compress) — goes into
the **bundle world** and Felix resolves them against each other. This keeps the OSGi behaviour
(SCR activation, capabilities, metatype, lifecycle) intact by construction, and is robust to the future.

**The one exception that would force OSGi-fying a third-party jar (user, 2026-06-19):** if our bundles
consumed a *Java service* (SPI / `ServiceLoader`) PROVIDED by that jar. A plain `ServiceLoader.load`
scans the current classloader; under OSGi, service discovery goes through the registry/TCCL, not the flat
classpath — so a third-party SPI served via system-export would be invisible to a bundle consuming it
(this is the very nature of #1565: gRPC transport discovery is a TCCL `ServiceLoader`, which is why gRPC
stays flat in the HOST world). **VERIFIED INACTIVE for R4** (grep 2026-06-19): the only `ServiceLoader.load`
calls inside `osgi/` bundles load OUR OWN SPIs (`ManifestSynthesisService`, `NodeEnvContributor` —
`io.seedmatic.*.port.*`, the paths SCR replaces); every `META-INF/services` file names an `io.seedmatic` type;
ZERO third-party SPI is consumed. So no third-party jar needs bundle-ifying on this ground — cdk8s/jackson/
snakeyaml/etc. provide no Java service we consume. The criterion holds with no active exception.

★ PATH CHOSEN (user, 2026-06-19): **path 2 — provide the bundles to the framework**, NOT path 1
(everything via system-export). seed-master's classpath is split into two worlds: a minimal flat HOST
world (Felix + Pulumi + grpc-netty + seed-master code + the shared `-port` packages + the not-designed-for-OSGi
jars via system-export) and the OSGi world (intact bundles Felix installs and resolves). The embed
mechanics are the easy part (maven-dependency-plugin `copy` → jars as `META-INF/bundles/*.jar` resources of
the exec-jar; the pom already does the analogous `unpack` for manifests-d).

★ The table below was the design INTENT (carto 2026-06-19). What R4 ACTUALLY SHIPPED is narrower — see
the "as shipped" note after it; the criterion (designed-for-OSGi) is sound, but the minimal proven set
embeds fewer bundles than the carto projected. The note reflects the SHIPPED reality (corrected
2026-06-20 against the exec-jar contents).

Design-intent partition (carto 2026-06-19):

| module/dep | designed for OSGi? | intended R4 treatment |
|---|---|---|
| `manifests-core` | YES — 10 OSGI-INF, Service-Component, Provide-Capability (the 3 seam services + 6 NodeEnvContributor + registry) | **embedded + installed as a bundle** |
| `manifests-port`, `netplan-port` | YES — bundle contracts | system export (single shared copy for the typed seam — see corollary) |
| `unitrepo-core`, `cdk8s-systemd` | YES — bundle libraries | (carto guessed: embedded; SHIPPED: flat — see below) |
| jackson / snakeyaml / slf4j / commons-compress | YES — versioned OSGi bundles | (carto guessed: embedded; SHIPPED: flat, except slf4j now provided by pax inside the framework) |
| `org.cdk8s`, `software.constructs` | NO — jsii jars, never bundles | system-export (flat, natural place) |

★ AS SHIPPED (verified against `seed-master-…-exec.jar` 2026-06-20) — `META-INF/bundles/` holds EXACTLY
FIVE jars: `manifests-core`, `org.apache.felix.scr`, `org.apache.felix.resolver`, `pax-logging-api`,
`pax-logging-logback`. Everything else (unitrepo-core, cdk8s-systemd, netplan-core, jackson, snakeyaml,
commons-compress, the jsii jars) stays FLAT on the host classpath and reaches the bundles via
system.packages.extra. This agrees with [[r4-resolver-service-ification]] §"C-bundles scope, corrected"
(unitrepo-core + cdk8s-systemd stay flat — pure libraries, zero @Component, after WI-C0 unitrepo-core
imports only `org.osgi.service.resolver`). The invariant still holds: only modules with ACTIVE OSGi
behaviour (manifests-core's SCR components, felix.scr/resolver runtime, pax LogService) need to be intact
bundles; the rest are passive types served flat. The minimal embedded set is a feature, not a shortfall —
fewer moving bundles, same proven seam. (netplan-core would join the embedded set only when netplan-cli
is migrated to boot Felix — the per-entrypoint embed-set point, [[osgi-runtime-r4-resume-state]] dominoes.)

## A THIRD lever — local wrap (measured 2026-06-21, jGiven spike)

The criterion above gives a binary: designed-for-OSGi → bundle world; not → flat system-export. The
jGiven spike ([[jgiven-osgi-wrap-spike-verdict]]) measured a THIRD option for a header-less jar, and it
does NOT weaken the invariant — it adds a lever the invariant didn't name. When a not-designed-for-OSGi
jar has a dependency tail that is *already entirely bundles* (jGiven's is: guava/gson/byte-buddy/… all
carry a Bundle-SymbolicName; only the two jgiven jars lack headers), you can **wrap it locally** into the
bundle world (bnd stamps headers + `Export-Package`, imports the rest as stock bundles) instead of
flattening it. You CHOOSE the wrap over the flat export when the jar must *participate* in OSGi —
resolve, host fragments, be reached through the registry — not merely lend its types. For jGiven that
participation is the fragment-test model (white-box in-container). Cost stayed LOCAL: one
`bootdelegation=sun.misc`, one forced fragment import, no `DynamicImport-Package: *`. The flat
system-export remains correct for a jar that only needs to lend types (cdk8s/jsii); the wrap is for one
that needs to act. Test-scope only — no production bundle imports jGiven.

## Single-exporter corollary (R1 hazard) — CHECKED on the real manifest 2026-06-19

Exactly **one** exporter per package, else the R1 split: two Class copies → the host's typed
`getService(ManifestSynthesisService.class)` silently misses ([[osgi-runtime-r1-scr-state]] §"ONE exporter
of the shared api package"). Checked manifests-core's bnd manifest on the worktree:

- **The `-port` packages are SAFE.** manifests-core exports NONE of them (Export-Package = `manifests`,
  `manifests.domain`, `manifests.node`, `manifests.profiles`, `manifests.units.runtime.flox` — zero
  `.port`). It only IMPORTS the 4 `-port` packages. So when OsgiRuntime re-exports them (versioned `1.0`)
  from the system bundle, the system bundle is the SOLE exporter → no split on the typed seam path. (An
  earlier note here claimed a `port.profiles` substitution-export — that was a hand-unfold misread; the
  clean manifest disproves it.)
- **Residual, packaging-dependent:** manifests-core DOES substitution-re-export its OWN internal
  `manifests.node` + `manifests.profiles` (imports+exports both). The host imports NEITHER (B left the
  host on `-port` only), so the typed seam is unaffected. The only question is whether OsgiRuntime should
  still mirror those onto the system bundle once manifests-core leaves the flat shade (A) — to settle with
  the exec-jar contents in hand, NOT before. OsgiRuntime derives versioned exports (lower-bound kept), so
  it does not reintroduce the unversioned-0.0.0 form that caused the original R1 red.

The R4 host-scope embedded-Felix test catches any miswire (awaitService returns null / ClassCastException),
so a split fails loudly, not silently.

## Where it's written

- Spec encadré: `wip/specs/2026-06-18-osgi-runtime-migration-design.adoc` §4.1 (IMPORTANT box).
- To fold into the atlas runtime view + an OSGi-runtime architecture doc when R4 ships (per CLAUDE.md
  doc standard — AsciiDoc + the anti-pattern ❌/✅).

See [[osgi-runtime-r4-boot-seam-state]] (THE SPEC)
[[osgi-runtime-r1-scr-state]] (single-exporter trick) [[hub:model-substrate-alignment]] (OSGi describes,
host actualises) [[system-space-world-universe-glossary]] (osgi world vs host world).
