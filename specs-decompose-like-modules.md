---
name: specs-decompose-like-modules
description: "Principle (user, 2026-07-08): the spec decomposition should DRAW INSPIRATION from the module decomposition's DISCIPLINE — not align 1:1 onto modules. The modules taught a METHOD (orthogonal axes, decompose-by-nature, strict no-mixing, one home per thing); apply that method to specs, which may have their OWN axes. Symptom that triggered it: pipeline-spec.adoc mixes 3 natures — the exact no-mixing rule a module never violates."
metadata:
  type: feedback
---

**The principle (user, 2026-07-08, sharpened):** "on a fait un gros travail sur le découpage en
modules ; on devrait pouvoir en tirer un ENSEIGNEMENT pour le découpage des specs" — then the key
correction: *"je ne dis pas qu'ils devraient s'ALIGNER sur les modules, mais s'en INSPIRER."* NOT
"one spec per module" (the 1:1 trap). The modules taught a decomposition METHOD; transpose the METHOD,
not the map. Specs may keep their OWN axes (e.g. transverse-pattern vs subsystem-view vs
component-spec) — as long as they apply the discipline the modules proved:

1. *Orthogonal axes, not a flat list.* A module is an INTERSECTION (`cluster-edge` = domain × role),
   which is what makes it findable — not "a thing in a pile".
2. *Nature before subject.* A `-port` is nature=contract, `-edge`=external-contact, `-core`=logic. We
   file by NATURE, not by topic.
3. *Strict no-mixing (absolute uniformity).* No module ever fuses two roles. That is exactly the rule
   `pipeline-spec` breaks — 3 natures in one doc — for the same reason a module never mixes port+edge.
4. *One home per thing, so you know where to look* (the `seed-bdd` argument).

So the lesson is NOT "map specs onto modules" — it is "decompose specs BY NATURE, with the same rigor
the modules decompose by role." A spec must not fuse "the BDD-as-engine thesis" (nature: a transverse
pattern) with "the seed orchestration" (nature: a subsystem architecture), just as a module never fuses
port and edge.

**The module decomposition (the disciplined structure, two axes):**

- *domain axis*: auth, bbox, cluster, doctor, incus, manifests, netplan, systemd, unitrepo, (seed —
  emerging: the orchestration domain, with `seed-bdd` + `seed-broker`).
- *role axis*: `-port` / `-core` / `-edge` / `-bdd` / `-records` / `-spi` (+ `-fake`/`-test` fixtures).
- a module sits at the INTERSECTION: `cluster-edge` = domain cluster × role edge. Uniform, discoverable.

**The gap (measured 2026-07-08) — specs are HALF-aligned:**

- ALIGNED: the edge specs already follow modules 1:1 — `cluster-edge-spec`, `bbox-edge-spec`,
  `dbus-systemd-edge-spec`, `incus-edge-spec`, `auth-edge-spec`. The lesson is already applied there.
- NOT aligned: `pipeline-spec`, `engine-lifecycle-spec`, `staging-gates-governance`, `world-gateway`,
  the boot specs — cross-cutting CONCEPTS laid flat in `osgi/`, indexed on no axis. `pipeline-spec` is
  the worst symptom: it fuses 3 natures (the BDD-as-engine thesis · the seed orchestration
  GROUND/GATEWAY/APPLY · the fluent "avant") BECAUSE it is not situated on any axis.

**The rule this yields (a spec sits on the same axes as a module, or is explicitly transverse):**

| module axis | corresponding spec |
| --- | --- |
| domain (doctor, cluster, seed…) | a per-domain VIEW (`atlas/doctor.adoc`; `atlas/host-pipeline.adoc` → a `seed.adoc`?) |
| role (port, edge, core, bdd) | a per-role PATTERN spec (`port-edge-domain-ownership.adoc`; a `bdd.adoc` for the `-bdd` role) |
| foundation/seam | a per-module spec (`seed-broker` spec ✓, ex-world-gateway) |

**Answers the naming questions that triggered this:**

- `pipeline-spec` → `bdd.adoc`? YES — `bdd` IS a module role (`cluster-bdd`, `seed-bdd`), so a
  `bdd.adoc` documents the `-bdd` role the way `port-edge-domain-ownership.adoc` documents port/edge/core.
- a `seed.adoc`? YES — `seed` is becoming a DOMAIN (orchestration; owns `seed-bdd` + drives the
  `seed-broker`), so a `seed.adoc` is its view, as `doctor.adoc` is the doctor domain's.

**NOT YET DECIDED (the user chose "map first, decide after"; this note is the map).** The re-cut of
`pipeline-spec` into `bdd.adoc` (thesis) + `seed.adoc` (orchestration) + the archived avant, and whether
domain views live under `atlas/` (L1 view) or `osgi/` (design spec), is the next step — decided ON this
map, weighing the ~30-link blast radius of a rename (README, integration-atlas, ~28 docs link to
pipeline-spec). Governance to respect: SPEC_COVERAGE (exported types need a doc), the README nav, the
bidirectional cross-ref discipline (CLAUDE.md). See [[gateway-is-rest-in-jvm-insight]]
[[cluster-seed-execution-state]] (the *Test/*Scenario/-core/-bdd placement rule this extends to specs).
