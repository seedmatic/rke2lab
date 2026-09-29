---
name: system-space-world-universe-glossary
description: "The precise vocabulary for the rke2lab decomposition, settled 2026-06-19. system = THE WHOLE (osgi+host worlds joined, running); space = a top-level LAYOUT dir (osgi/ host/ exec/, sorted by purity + nature axes); world = a RUNTIME classloader region (OSGi world = bundle loaders no gRPC; HOST world = flat loader holds gRPC); universe = RESERVED for the resolver (UnitResolver candidate set / ManifestsUniverse) — NOT a synonym for system. exec/ is the NATURE axis and its role is to MATERIALISE the system (seed-master embeds both worlds into one self-contained launchable jar). The triad: osgi DESCRIBES, host ACTUALISES, exec MATERIALISES. Lives in the atlas's 'two spaces' NOTE."
metadata:
  node_type: memory
  type: reference
---

## Why this glossary exists

We had used univers/monde/espace/axe loosely. The trigger: the atlas said "two spaces" (osgi/host) but
the layout had acted a third top-level dir `exec/` (the nature axis), and the user noted "exec is the
materialisation of the universes" + "the host/osgi worlds are PART OF a universe" — pointing at a real
gap: there was no word for THE WHOLE. Carto of actual usage (atlas + specs + code) fixed each term.

## The terms (each used precisely; do not blur)

- **system** — THE WHOLE: the two worlds (OSGi + host) joined into one thing that runs. The worlds/spaces
  are PARTS OF the system; the system is NOT a third space. (We already say it: "seed-master IS the
  system", "more deterministic system".) This is the word that was missing.
- **space** — a top-level LAYOUT directory: `osgi/` (pure, describes), `host/` (actualises over Pulumi),
  `exec/` (executables). Sorted by purity (osgi↔host) + nature (exec). "Two spaces" names the PURITY
  seam; `exec/` is the orthogonal NATURE axis, NOT a third purity space — so the atlas "two spaces"
  frame stays correct.
- **world** — a RUNTIME classloader region: OSGi world (bundle classloaders, NO gRPC ever) vs HOST world
  (one flat classloader, holds gRPC). Same osgi/host line as the spaces, seen at runtime not at layout.
- **universe** — RESERVED for the resolver sense only: `UnitResolver`'s candidate set
  (`UnitResolver(List<UnitResource> universe)`), `ManifestsUniverse`, "the manifests universe". NOT a
  synonym for "system". Renaming the resolver field was considered and rejected (code churn for no gain);
  instead "system" fills the whole-system slot and "universe" stays the resolver's.
- **port** — an *OSGi port*: the pure boundary model (interfaces + records, zero engine imports) that the
  HOST calls and an OSGi bundle implements. By the purity axis its nature is osgi/, so it lives in
  `osgi/<domain>/<domain>-port` — and the module/package name is reduced to bare **`port`** (not
  `osgi-port`) BECAUSE the osgi/ belonging is already carried by the space; prefixing `osgi-` would
  re-state what the location says. Hexagonally the port belongs to the DOMAIN, not the adapter: both the
  host and the impl depend on it (DIP). Versioned bundle at BUILD, NOT installed into Felix — at runtime
  it is **system-exported** (`system.packages.extra`, flat) and **host-loaded** (the resolution reads
  osgi port ← system-exported ← host-loaded: the host loads the class, resolved via the system bundle's
  export, back to the port defined in osgi/). DISTINCT from the *bundle/host contract* below.
  Named-by-NATURE taxonomy (`-port`/`-spi`/`-api`): `-spi` = an interface resolved INSIDE the osgi world
  the dependant *implements* (e.g. `unitrepo-handler-spi`); `-api` = a surface the dependant *calls*; a
  port is BOTH at once across the frontier, so it is named by concern, never `.api`/`.spi`
  ([[rename-contract-to-port-state]]).
- **bundle/host contract** — DO NOT conflate with *port*. This is the capability-resolution seam:
  `Require osgi.extender` + `SectionReader` (config view), a bundle declaring a need the host satisfies.
  It is a contract (a declared/satisfied capability), not a typed model crossing the frontier. The atlas
  keeps the word "contract" for THIS; what used to be loosely called "the contract" as a typed boundary
  model is now the *port*.
- **edge** — a boundary crossing; what makes a *port* necessary (the port is the membrane, the edge is
  what lies on the far side). TWO SPECIES, by which boundary is crossed (validated on the reactor
  2026-06-21):
  - **external edge** — crosses the SYSTEM boundary toward a system OUTSIDE ours: the Pulumi state
    backend, the k8s cluster, the systemd/dbus endpoint, incus, ssh-to-age. MUTUALISED BY TARGET, not
    per domain — one `pulumi-edge` shared across every domain touching Pulumi, named `<target>-edge`.
    Often non-playable. "edge" beats ingress/egress for the module name (avoids the k8s Ingress
    collision; an external edge is bidirectional so a directional word would mislabel it).
  - **internal edge** — a domain's face turned toward the rest of OUR system; crossed by other
    domains/orchestration through a pure port. It DISSOLVES the old "transitive port" category:
    `manifests-port`/`netplan-port` are internal edges, not orphan transitive ports — so *every port
    traces to an edge* with no exception. A single domain can carry BOTH faces (doctor: `SnapshotSource`
    external, its consumer side internal). PRESCRIPTIVE, not just descriptive — not satisfied by "has a
    port package"; its value is the LEAKAGE its absence allows. Tell: do neighbors import the core's
    IMPL types or call a contract? manifests/netplan = CLEAN (port types only); doctor = MISSING (40+
    controlplane files import `doctor-core` impl types directly — the smoking gun). See
    [[doctor-internal-edge-debt]].
- **ingress / egress** — the DIRECTION of an operation across the membrane, used in prose and method
  names, NEVER as a module name: *ingress* = read FROM the world (`SnapshotSource.timeline()`), *egress*
  = write TO the world (`LedgerWriter.up()`). The same edge does both. Kept off module names so the k8s
  meaning never collides.
- **the port is the membrane of BOTH worlds, not just OSGi's** — load-bearing clarification (2026-06-21):
  the port is reached IDENTICALLY whether the caller is in the OSGi world (core) or the host world. The
  host has no privileged direct path to the world — it too passes through the port. There is never a
  second route ("direct host access" + "port for OSGi"); there is ONE door. Consequence: if an edge's
  impl migrates host↔osgi (because its lib becomes playable, below), callers are unaffected — they were
  already going through the port. The port decouples from the edge's own world-of-residence.
- **playability (the frontier criterion)** — the test that decides whether a type's impl can live in
  the OSGi world: *can the third-party libraries it needs resolve and run inside a Felix bundle?* Pure
  JDK (`java.nio` filesystem, `ProcessBuilder` commands) and Jackson ARE playable — touching the disk or
  spawning a process is NEVER the disqualifier. Non-playable: `com.pulumi.*` (native deps, ServiceLoader,
  classloader assumptions), jgiven `com.tngtech.*`. CRITICAL: playability is ORTHOGONAL to edge —
  rendering an operation playable does NOT eliminate its port (reading the Pulumi state in pure JDK still
  REACHES the state backend; it is still an edge). Playability decides only WHERE the edge's impl lives
  (host vs osgi), never WHETHER a port is needed. The detonator: `BootstrapConfig` is a pure record, so a
  type "host because it takes BootstrapConfig" is actually playable → it is `core` or a `port`, not an
  edge. Writing a port thus becomes a DERIVATION, not a judgement: "is this type's lib playable?" (a
  closed, code-probable question) replaces "does this describe or actualise?" (a fuzzy one).

## The role triad (the verbs)

- `osgi/` **describes** (pure models, the ports + impls).
- `host/` **actualises** the description over the Pulumi engine.
- `exec/` **materialises** the system: `seed-master` embeds BOTH worlds — the OSGi bundles (describe) +
  the host adapters (actualise) — into ONE self-contained launchable jar that boots Felix inside
  `Pulumi.run` (R4). This is the role the terminology lacked a verb for.

## The three layout axes (target-layout spec §2, unchanged — this glossary just names their product)

- *purity (WHO):* osgi describes / host actualises.
- *direction (WHICH WAY):* north (Provide/offer) vs south (Require/depend).
- *nature (WHAT KIND):* library vs executable; `exec/` carves the executables out, orthogonal to purity.

## Where it is written

The atlas (`docs/architecture/integration-atlas.adoc`) carries this as a NOTE in the "two spaces"
section. When writing specs/docs/memory, use these words with these meanings — precision of naming is a
project principle ([[every-module-has-a-description]] sibling discipline). See
[[osgi-runtime-r4-boot-seam-state]] (the self-contained jar that materialises the system),
[[rename-contract-to-port-state]] (the `-port`/`-spi`/`-api` taxonomy; contract→port rename),
[[api-extraction-tri-carto-state]] (the original sort, since superseded — the port lives in osgi/, not
the host world as that early note framed it), the atlas "two spaces" + runtime view.
