# Memory index — rke2lab

Project memory for rke2lab. Cross-cutting facts (profile, conventions, principles)
+ cross-repo chantiers live in the **hub** ([[hub:MEMORY]] at
`/private/var/lib/git/nxmatic/claude-hub.d/main`), auto-loaded as session root.

Links: `[[name]]` = rke2lab-local, `[[hub:name]]` = hub. The edge IS the
coordinate — it resolves without consulting any index (see
`.claude/hub/memory/MEMORY-STRUCTURE-SPEC.md`).

## How this index is structured — READ BEFORE ADDING AN ENTRY

**This file is injected at session start and is capped at ~24 KB. Anything past
the cap is silently dropped, so entries at the end become invisible.** It reached
138 KB / 224 entries (5.6× the cap) because entries accreted here instead of in
their own files: the average line had grown to 609 characters against a 200-char
rule.

So the index is a **node with children**, not a flat list:

- **This file** holds only section pointers + what is in flight. Keep it ≤6 KB.
- **`INDEX-<section>.md`** holds that section's entries, one line each
  (~200 chars: title, one-line hook, `See [[links]]`). Loaded **on demand** —
  they are not capped, but the 200-char rule still applies, because a line that
  needs a paragraph is a line whose detail belongs in its topic file.
- **Topic files** hold everything else. They are 3-10× their index line already;
  that is the intended ratio.

Adding a fact: write the topic file, then one ≤200-char line in the right
`INDEX-*.md`. Add to *this* file only if it is actively in flight.

## In flight

- [★ nixos substrate — store /nix/store en EROFS+overlay (SHIPPÉ 2026-09-18, ndh)](nerd-nixos-image-build-slow-not-zfs-on-zfs.md) — build VM imbriquée ~6 min contre ~50 ; runtime validé sur nikopol. Reste : renew bioskop. See [[nerd-nixos-tart-vm-renew-procedure]] [[materializer-corp-mac-identity-gcroots]].
- [★ config RKE2 — livraison uniforme `nix run <branche>#install-rke2-config`](rke2-config-reconciliation-nixrun-delivery.md) — design tranché, code pas commencé ; plan `.claude/rke2-config-reconciliation-plan.md`.
- [★ workload grow — fondations (modèle B tranché)](workload-grow-foundations-resume.md) — `bioskop-wrkld` bloqué sur fondations mono-cluster ; roadmap `.claude/workload-grow-foundations-plan.md`.
- [★★ Cellier TRANSACTIONNEL — design convergé, code pas commencé](cellar-transactional-design-state.md) — ScenarioCellar universel + SeedRunLedger ; 4 étapes de fondation à coder.
- [★ materializer nerd-nixos sur le Mac corp — identité + gcroot](materializer-corp-mac-identity-gcroots.md) — corrigé dans le code (ndh `8c33cd74`/`e2c6a302`) ; reste la question du hostProfile et la distribution des clés.

## Sections

| Section | Entrées | Index |
|---|---|---|
| Conventions / feedback | 13 | [INDEX-conventions.md](INDEX-conventions.md) |
| Active chantiers — flox runtime | 14 | [INDEX-flox-runtime.md](INDEX-flox-runtime.md) |
| Active chantiers — OSGi / unitrepo / pipeline | 78 | [INDEX-osgi-pipeline.md](INDEX-osgi-pipeline.md) |
| OSGi invariants / decisions / rules | 13 | [INDEX-osgi-invariants.md](INDEX-osgi-invariants.md) |
| OSGi runtime migration — SHIPPED slices | 5 | [INDEX-osgi-shipped.md](INDEX-osgi-shipped.md) |
| OSGi cleanup / module layout — SHIPPED slices | 12 | [INDEX-osgi-cleanup-shipped.md](INDEX-osgi-cleanup-shipped.md) |
| Doctor / health system | 14 | [INDEX-doctor.md](INDEX-doctor.md) |
| Infra / manifests / config | 12 | [INDEX-infra-manifests.md](INDEX-infra-manifests.md) |
| Rules / patterns / gotchas | 24 | [INDEX-rules-gotchas.md](INDEX-rules-gotchas.md) |
| Memory / workspace mechanics | 6 | [INDEX-memory-mechanics.md](INDEX-memory-mechanics.md) |
| Backlogs / misc | 33 | [INDEX-backlogs.md](INDEX-backlogs.md) |

## Known debt in this memory

- `INDEX-osgi-pipeline.md` carries 78 entries / 62 KB — 44% of all memory index
  weight, average 780 chars per line. Nothing in it has been touched since
  2026-08-14. Triage started: ~21 `type: project` entries are dormant chantiers
  and archivable; 7 are `type: feedback` (durable, must stay); 4 are principles
  mistyped as `project` (`prefer-osgi-edge-three-reasons`,
  `orchestration-purity-benefit`, `gateway-is-rest-in-jvm-insight`,
  `memory-synthesis-prune-the-how`) and want retyping, not archiving.
- `cluster-seed-execution-state` is listed **twice** in that section.
- 33 topic files are untracked in git, so they do not survive a machine change.
