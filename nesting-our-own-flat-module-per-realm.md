---
name: nesting-our-own-flat-module-per-realm
description: Pattern for sharing ONE source of jackson-using logic across the host/OSGi realms without it crossing the String-only seam. TWO forms — PREFER embed;type=library (an autonomous dual-realm bundle, jackson's own treatment, multi-domain, single exporter; world-gateway 2D T5 2026-07-01) over the older flat-jar-nested-in-one-domain (still valid but couples a foundation concern to one domain, splits on a 2nd OSGi consumer). Exemplar for both: gateway-document-codec / DocumentCodec.
metadata:
  type: reference
---

## The problem it solves

You have logic BOTH realms need (host-flat + OSGi-bundle) that depends on a realm-isolated library
(jackson — since `ae46278b` each realm loads its own copy). You want ONE source compiled into TWO
runtime copies, each bound to its realm's jackson, WITHOUT the logic crossing the world-gateway seam
(the seam is `type=seam`, String-only; a type crossing it reopens what 2C closed, and jackson on the
seam surface breaks REALM_BOUNDARY/DUPLICATE_REALM_CLASS).

## PREFERRED form — an autonomous dual-realm library bundle (`embed; type=library`)

This is jackson's OWN treatment, generalized to our code — the right form when the logic is a
FOUNDATION concern any realm/domain may need (the codec is: host + any OSGi domain). Proven
2026-07-01 (world-gateway 2D T5).

1. **A real OSGi bundle** in `osgi/foundation/`: `bnd.bnd` has a `Bundle-SymbolicName`,
   `Export-Package: <the api pkg>`, `Import-Package` of its realm-isolated dep (jackson) + any seam
   it uses (bnd emits these from bytecode), and the marker `Provide-Capability:
   io.seedmatic.rke2lab.embed; type=library`. Keep the jackson glue in a NON-exported `.internal`
   subpackage (only the API type is exported) so SPEC_COVERAGE need not document the glue.
2. **Staging treats `type=library` as dual** (`StagingClosure.isRealmLibrary` returns
   `b.embed().isLibrary()`): staged under `META-INF/bundles/` AND kept flat in the host uber-jar
   (in `realmLibraryGas`, so `shadeExcludeGas` does NOT exclude it). This is the ONLY `embed` type
   that lives in both realms — model/edge/record are bundle-only, seam is system-exported.
   `EmbedCapability.TYPE_LIBRARY` + `isLibrary()`; also listed in `INSTALL_FILTER` (documentary —
   runtime installs staged jars by presence via `BundleIndex`, not by that filter).
3. **OSGi consumers IMPORT the package** (a normal compile dep; bnd emits `Import-Package`). **Host**
   depends on it normally → shaded flat. Verify DUAL: `META-INF/bundles/<lib>.jar` present in the
   uber-jar AND the api `.class` also flat at the top level.

Why it does NOT trip the gates: its exported package is `flat ∧ exported` but NOT a seam surface, so
`DUPLICATE_REALM_CLASS` (fires on `flat ∧ seamSurface`) exempts it, exactly like jackson. No type of
it crosses the seam, so `REALM_BOUNDARY` is clean. Dependency is one-way `library → seam`.

## OLDER form — flat-jar nested in one domain bundle (`-includeresource;lib:=true`)

Still technically valid, but INFERIOR when the logic is foundation/multi-domain: it makes the host
domain "own" a foundation concern, and a SECOND OSGi consumer forces a split package. Use only when
the logic is genuinely private to ONE domain and will never be foundation. The module is a plain jar
(NO `Bundle-SymbolicName`, NO embed cap); the domain bundle nests it in `bnd.bnd`
(`-includeresource: <artifact>-*.jar;lib:=true`) so its classes ride that bundle's Bundle-ClassPath;
the host shades the same jar flat. It stays nested-PRIVATE (never exported) — if a 2nd OSGi consumer
appears, promote it to `type=library` rather than nest-and-export from two bundles.

## Naming lesson

Name the module/package for what it serves, specific enough to avoid a future split: artifact
`gateway-document-codec`, package `io.seedmatic.rke2lab.world.gateway.codec` — a SIBLING of the seam's
`…world.gateway.port`, NEVER inside the seam (the seam shares DATA in one copy; this shares
LOGIC+jackson in two isolated copies — opposite contracts). BSN
`io.seedmatic.rke2lab.gateway.document.codec`. See [[realm-library-isolation-state]]
[[codec-foundation-single-exporter-when-needed-backlog]] [[world-gateway-2d-execution-state]].
