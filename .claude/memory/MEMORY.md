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

- [★ nixos substrate — store /nix/store en EROFS+overlay (SHIPPÉ 2026-09-18, ndh)](nerd-nixos-image-build-slow-not-zfs-on-zfs.md) — build VM imbriquée ~6 min contre ~50. **2026-09-19 : LES DEUX NŒUDS TOURNENT DESSUS.** `{bioskop,nikopol}-nixos` up, `/nix/.ro-store` = erofs sur `vdf` (ro=1 côté bioskop), `/nix/store` = overlay, tank+recover ONLINE. bioskop a matérialisé le bundle `h01wjv82` (disques de pool retaillés à 1540 MiB) — la réserve « jamais booté » est LEVÉE, vérifiée par le marqueur `store.img.source`. See [[nerd-nixos-tart-vm-renew-procedure]] [[materializer-corp-mac-identity-gcroots]].
- [★★ /nix/store en PILE de couches EROFS, une par génération — PHASES 0/1/2 LIVRÉES](erofs-store-layer-stack-vision.md) — **vérifié sur nikopol-nixos le 2026-09-20 : upper 5,3 GiB → 21 Ko, pool `tank` 7,90 GiB → 26,1 Mo, bascule de génération en 1 s.** Spec rke2lab `a86a5a9f4` ; ndh `5383034d` (pile de 1, contenu constant), `6d25264c` (2 couches + delta), `713826dd`. **Phase 3 : approche STRUCTURELLE tranchée et MESURÉE** — L2 générique partagée 1098 chemins/7,49 GiB, L3 par hôte **77 chemins/39,6 Mo** (le par-hôte divisé par ~190), lest 40 Mo. ⚠️ patch NON COMMITTÉ dans `ndh/flake.nix` (`nixosConfigurations.nerd-runtime`, évalue et construit). **Tâche du jour : matérialiser la nouvelle couche via la nouvelle version de nerd-nixos.** See [[destructive-gate-needs-three-valued-probe]].
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

## Renamed since these notes were written — translate before searching

Checked against `feature/nixos-node-substrate` on 2026-09-19. Memory predating a
rename keeps the old word, so a literal search finds nothing in the code.

- **`world-gateway` → `seed-broker-port`** (`acd68a510`). Live branch: `seed-broker`
  in 392 files, `world-gateway` in 14 (docs only). Memory still says
  `world-gateway` in 49 files / 27 index entries — the `world-gateway-2a…2e`
  entries name *chantier phases*, so their titles stay; only the vocabulary moved.
- Spec chain, all three now one file `docs/architecture/osgi/seed-broker-spec.adoc`:
  `multiplexor-spec.adoc` → `world-exchange-spec.adoc` (`7e289e236`) → renamed
  again with the word (`adc113f61`, `acd68a510`).
- Doc re-cut by nature (`e0945ec71`): `osgi/pipeline-spec.adoc` →
  `docs/architecture/bdd/bdd.adoc`; `atlas/host-pipeline.adoc` →
  `docs/architecture/atlas/seed.adoc`. Fully-qualified citations were fixed on
  09-19; bare `pipeline-spec.adoc` mentions left alone — several are *about* the
  re-cut, and `pipeline-spec-recut-plan.md` even carries the `git mv` command.
- `osgi/two-gates-spec.adoc` is cited by two entries but **never existed** in any
  commit — a promised doc that was never written.
- `docs/manifests-architecture.adoc` → `docs/architecture/manifests/manifests-architecture.adoc`.
- **Not stale, cross-repo:** `docs/host-builder-phases.adoc`,
  `docs/operator-commands.adoc`, `docs/vm-operator-runbook.adoc` live in **ndh**.
  22 of 76 cited paths looked missing; a third were simply another repo's.

## Known debt in this memory

- **201 of 224 entries exceed the 200-char rule** (average 609). Compressing them
  is safe — every topic file is already 3-10× its index line — but verify per
  entry first: `cluster-seed-execution-state` had its newest decision (fork B,
  2026-07-08) living ONLY in the index line while the file stopped at 07-07.
  Shortening blindly loses facts; that one was rescued into the file on 09-19.
- `INDEX-osgi-pipeline.md` is 77 entries / 62 KB, nothing committed after
  2026-08-14. It carries a `## À trancher` list of 16 dormant candidates —
  **proposed, nothing moved**: a keyword+date heuristic misjudged three entries
  there, so the call needs a human who knows the chantier.
- **Dead `[[link]]` check, run 2026-09-19** (296 unique refs / 1657 occurrences):
  252 resolved, 20 were hub files missing their `hub:` prefix — the structure
  spec's migration step 4, never done — now fixed across 87 occurrences. Every
  index entry target exists except one, which never existed in git and whose
  content lived only in its index line: rebuilt as
  `checkpoint-identity-to-seam-backlog.md`.
  **16 refs / 40 occurrences remain genuinely dead** — no file in this memory nor
  in the hub, and no near-name suggesting a rename: `realm-boundary-gate` (7×),
  `flox-controller-build-deploy-state` (6×),
  `cold-start-cleanup-and-funnel-cert-persistence` (6×),
  `spec-figure-first-reading-loop` (4×), `c4-diagrams-flowchart-not-native-dsl` (3×),
  `port-edge-domain-ownership`, `ghapp-webhook-reconcile-and-funnel-rename`,
  `caprke2-byo-ca-secret-contract` (2× each), then `thread-manifestations`,
  `systematic-debugging`, `runtime-view`, `render-config`,
  `pipeline-spec-legibility-cleanup-post-go`,
  `flox-gate-secret-flow-devlxd-then-certmanager`, `flox-envs-runtime-crd-delivery`,
  `flox-carrier-containerize-spike`. Each is a fact someone meant to write and
  did not; the citing entries still hold the gist.
  Re-run: extract `\[\[name\]\]` from `*.md`, resolve bare names against this dir
  and `hub:` ones against the hub memory dir. Ignore `name`/`x`/`links`/`link` —
  they are syntax examples in the header, not refs.
