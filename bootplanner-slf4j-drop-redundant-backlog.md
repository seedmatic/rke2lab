---
name: bootplanner-slf4j-drop-redundant-backlog
description: Minor cleanup backlog — after realm-library-isolation, the dedicated org.slf4j removal in BootPlanner.deriveSystemExports (~line 199) is redundant; the generalized installed-bundle-export removal already strips it. Defensible R1-scar guard, not urgent.
metadata:
  type: project
---

After the realm-library-isolation increment (2026-06-30), `BootPlanner.deriveSystemExports`
(osgi/runtime/boot/boot-discovery) has TWO removals that now overlap:

1. line ~159 (generalized this increment): `exports.removeIf(installedExportedPackages.contains(...))`
   — strips any package an INSTALLED bundle exports.
2. line ~199 (pre-existing R1 scar): the dedicated drop of `org.slf4j` when pax is present, so
   pax-logging-api is slf4j's sole in-framework provider (avoids splitting the binder).

The final whole-increment review (opus) found #2 is now dead in every reachable topology: domain
bundles import `org.slf4j` (it enters `exports`), but pax-logging-api AND slf4j-api both EXPORT
`org.slf4j` and are in `stack` for the embedded `all()` boot — so #1 already removes it before #2
runs. The line-199 guard (`!paxLoggingBundles().isEmpty() || embedsBootStack()`) only fires when pax
is in `stack`, which is exactly when #1 has already stripped slf4j.

**Decision:** NOT actioned in the isolation increment (out of scope — the spec only mandated deleting
the slf4j hand-list in DuplicateRealmClass, which was done). Kept as a defensible narrow guard for the
slf4j-binder-split scar. To clean up later: delete the line-199 block, fold the R1-scar rationale into
the line-156-158 comment, and re-run the embedded boot tests (HostSeamEmbeddedFelixTest +
EmbeddedBundlesBootTest) to confirm pax stays slf4j's sole provider. Low priority. See
[[realm-library-isolation-state]].
