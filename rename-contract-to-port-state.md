---
name: rename-contract-to-port-state
description: "REFACTOR (refactor/osgi-cleanup, on top of the 4c2631f8 bridge→contract + osgi/ re-placement): final rename contract→port for the 3 hexagonal boundary modules, AND the nature→name taxonomy that drove it. DONE + build-green 2026-06-19, NOT yet committed."
metadata:
  type: project
---

The closing move of the OSGi-cleanup slice: rename the 3 host↔osgi **boundary
ports** `-contract` → `-port`. Done on the worktree, `-Posgi clean package`
GREEN (35 modules, 0 skipped), **not yet committed** at time of writing.

**Why `port`, not `contract`** (user's call, 2026-06-19): `contract` is too
generic — *any* API is a "contract", so the word doesn't say what the module
*is*. `port` names the hexagonal nature precisely: the gateway point at the
frontier between two worlds (host ↔ osgi), like the maritime port where ships
**abord**. Rejected `osgi-port` (the port is a PURE model, zero osgi-runtime
import — prefixing `osgi-` would claim an belonging it doesn't have; and in
hexagonal the port belongs to the DOMAIN, not an adapter/techno). Rejected
`boundary`/`seam` (less precise than `port`).

**The taxonomy this slice crystallised — the name follows the NATURE, read off
one question: "what relation does the module hold with whoever depends on it?"**

| suffix | nature | test question | package |
|---|---|---|---|
| **`-port`** | boundary port host↔osgi | "both worlds depend on it, host-loaded + system-exported?" | by **concern**: `…<domain>.port[.sub]` — never `.api`/`.spi` |
| **`-spi`** | interface to **implement** (provider side) | "the dependant *implements* it?" | `…<x>.spi` |
| **`-api`** | surface to **call** (consumer side) | "the dependant *calls* it?" | `…<x>.api` |

Key insight that forces "by concern" for a port: **a port is BOTH api and spi
at once, depending on which side you look from** — the host *calls* it (api),
the impl *implements* it (spi). So you cannot name a port's package `.api` *or*
`.spi` without lying about the other half → name it by concern.

**What was renamed** (3 modules, build-green):
- dirs: `osgi/{manifests,netplan,systemd}/<x>-contract` → `<x>-port`
- packages: `manifests.contract[.node|.profiles]`→`.port`; `netplan.contract`→`.port`;
  systemd `systemdcontract.api`→**`systemd.port`** (this one ALSO fixed two pre-existing
  smells in one shot: the collapsed token `systemdcontract` and the parasitic
  `.api` on a port — see the test-question table).
- BSN + Export-Package in the 3 `bnd.bnd` aligned to `…<domain>.port`.
- artifactIds / `<module>` / deps across 10 poms (`build-parent`, `exec/seed-master`,
  the osgi aggregators + cores).
- 4 `META-INF/services` files in manifests-core git-mv'd to their `.port` FQN.
- 110 Java files: FQN rewrite (`perl -pi`, NOT `sed -i ''` — the empty-suffix arg
  gets mangled by zsh and silently no-ops).

**Deliberately NOT touched** — `contract` is overloaded in this repo: the
*conceptual* contracts `bootstrap-contract`, `incus-distribution-contract`,
`stagea-stageb-handoff-contract`, `config-bundle-host-contract` are unrelated
and were left alone. Historical memory snapshots (`[[rename-bridge-to-contract-state]]`,
`[[contract-placement-and-versioning-carto]]`, `[[api-extraction-tri-carto-state]]`)
and dated specs were NOT rewritten — they record what was true then; this note
supersedes them.

**Follow-up — DONE, separate commit** (user's call): `unitrepo-handler-api` →
`unitrepo-handler-spi`, module AND package (`…unitrepo.handler` →
`…unitrepo.handler.spi`). Its nature is an extender SPI resolved *inside* the
osgi world (the code says "the handler SPI"; capability
`osgi.extender=unitrepo.handler` wired by the resolver) — it never crosses the
host↔osgi frontier, so `-spi` tells the truth where `-contract`/`-port` would
lie. Fully self-contained: the SPI has no concrete implementor yet and
`unitrepo-core` doesn't even depend on the artifact, so ZERO external call-site —
just the module's own 2 files + bnd + 2 poms. NOTE the 3 surviving
`unitrepo.handler` strings (pom description, javadoc, `@Capability(name=…)`) are
the EXTENDER CAPABILITY NAME — independent of BSN/package, deliberately
unchanged. -Posgi green, 35 modules.

**Follow-up — DONE, same review** (user's call): the `-core`/`-port` split
exposed a DUPLICATED SOURCE OF TRUTH — `exec/seed-master`'s test fixture
`unitrepo/realgraph/ReactorModuleCatalog` transcribes reactor module ids BY HAND
("transcribed faithfully from the reactor poms, verified 2026-06-15"), and the
split silently drifted 3 of its 7 nodes (`systemd-contract` → now `systemd-port`;
the un-split `manifests`/`netplan` → now `-core`/`-port`). The build never caught
it: `ReactorModuleCatalogTest` only checks the fixture against ITSELF (size==8 +
its own id strings), never against real module names — same silent-drift class as
the historical `clusterApi`≠`cluster-api` bug. We did NOT re-sync the copy: the
whole `realgraph` fixture builds the resolver universe by hand purely to
demonstrate the standalone-resolver track, and that proof is SUPERSEDED once Felix
boots for real and resolves actually-installed bundles (R4). So we put a tombstone
instead: all 7 files of the `realgraph` package + a new `package-info.java` carry
`@Deprecated(forRemoval = true)` explaining the drift and condemning the package
to DELETION at R4 (the stale ids left as-is, documented "deliberately not
re-synced"). NOTE: `UnitResolver` itself is NOT deprecated — it wraps the Apache
Felix `ResolverImpl` and stays in PRODUCTION (`ManifestsVisitOrder`,
`ManifestsDomainRegistry`); only the hand-fed `realgraph` test universe dies.
-Posgi green, 35 modules, the 5 realgraph tests still run (0 skipped). The R4
removal is recorded in [[osgi-runtime-r4-boot-seam-state]] + [[java-cleanup-backlog]].

See [[osgi-cleanup-slice-state]] [[contract-placement-and-versioning-carto]]
[[java-cleanup-backlog]] [[osgi-runtime-r4-boot-seam-state]].
