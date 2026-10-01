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

- [★ deux décisions en attente de l'utilisateur (2026-09-20)](unmanaged-mac-ssh-material-chain.md) — (1) passer les auth-keys tailnet en `ephemeral = true` pour supprimer la cause des fantômes qui retiennent les noms [[tailnet-node-identity-ephemeral-ghosts]] ; (2) corriger l'identité `nxmatic`/`/Volumes/user-home` du profil nikopol et **activer nix-darwin en local sur le laptop corp** plutôt qu'écrire un enrôleur — risque : nix-darwin prend la main sur `/etc/ssh` et les shells d'une machine sous MDM.
- [★★★ flox envs — PRÉVU, PAS COMMENCÉ : séparer producteur / livraison / réalisation (décidé 2026-09-23)](references-resolve-at-build-not-runtime.md) — décision utilisateur : **les références se résolvent au BUILD, pas au runtime** ; le lock va dans git et voyage avec le rendu, l'annotation `relock` reste la *commande* du bump mais gagne une **portée par inputs** (tous par défaut). Trois rôles effondrés en un dans flox-controller, et les deux découpages sont DÉJÀ écrits dans ses propres commentaires (`the lock is NEVER produced by the controller`, `until split into a leader-elected cluster manager`) — la régression est `propagateRelock`, qui estampille chaque révision de catalogue en relock total. ⚠️ C'est un DaemonSet sans leader : N pods écrivent le même `status.Lock` pendant que chaque nœud réalise sa closure — masqué par les clusters mono-nœud. Le producteur doit **commiter dans git** (branche `flox-catalogue`, qui ne redéclenche pas le rendu) → plaide pour un pipeline Tekton, le droit d'écriture hors des nœuds. See [[seed-incluster-crd-and-binary-travel-by-two-pins]] [[node-env-gated-oneshots-skip-capn-nodes]].
- [★★★ nikopol-mgmt — modèle B tranché (2026-09-25) + PREMIER cas d'usage du clustermesh](nikopol-mgmt-federation-clustermesh-first-case.md) — bioskop **enfante** nikopol-mgmt puis il s'**auto-adopte** : le plan CAPI n'est qu'un échafaudage, mais le **clustermesh MGMT** (id 1 ↔ 3) est permanent — pré-armé depuis toujours, jamais eu de 2e membre, et son **appariement est out-of-band** (`clustermesh-remote-users` vide, aucun Secret `cilium-clustermesh`). Whiteboard `.claude/claude-preview.adoc`. ★ Incus **EN CLUSTER : RETENU** (rejet retiré le 09-25 — `database-client` garde l'itinérant hors du raft ; corrige la version antérieure de cette ligne). Étape 1 LIVRÉE + vivante : `fabric-br` et la zone appartiennent à NixOS, `nixos.<host>` résout des deux côtés. See [[bridges-leave-incus-forced-not-chosen]] [[single-owner-rule]].
- [★ config RKE2 — livraison uniforme `nix run <branche>#install-rke2-config`](rke2-config-reconciliation-nixrun-delivery.md) — design tranché, code pas commencé ; plan `.claude/rke2-config-reconciliation-plan.md`.
- [★ workload grow — fondations (modèle B tranché)](workload-grow-foundations-resume.md) — `bioskop-wrkld` bloqué sur fondations mono-cluster ; roadmap `.claude/workload-grow-foundations-plan.md`.
- [★★ Cellier TRANSACTIONNEL — design convergé, code pas commencé](cellar-transactional-design-state.md) — ScenarioCellar universel + SeedRunLedger ; 4 étapes de fondation à coder.
- [★ materializer nerd-nixos sur le Mac corp — identité + gcroot](materializer-corp-mac-identity-gcroots.md) — corrigé dans le code (ndh `8c33cd74`/`e2c6a302`) ; reste la question du hostProfile et la distribution des clés.

- [★★★ quand `nix eval` et `cat` se contredisent, soupçonner le DISQUE (2026-09-27, ~4 h perdues)](nix-store-and-etc-can-both-lie.md) — un `sudo tee` suit la chaîne `/etc/nix/machines → /etc/static → /nix/store/…` et écrit **DANS le store** ; nix ne rebâtit jamais un chemin existant, `--realise` se court-circuite, `--repair-path` ne fait que substituer, et `rm` désynchronise la base. Réflexe : **`nix-store --verify-path`**. Deux leurres à connaître : `/etc/nix-darwin` épingle une copie figée (un switch sans `--flake` est un no-op), et `.source` ≠ `.text` d'une même entrée `environment.etc`.
- [★★ le routage tailnet appartient aux HÔTES, pas aux pods (livré + poussé 2026-09-27)](tailnet-routing-owned-by-hosts-not-pods.md) — la CR `Connector` supprimée (rke2lab `acd0e45d4`), les segments vmnet annoncés par le bare-metal qui possède les bridges (ndh `34605639`) : la route survit au cluster et un cluster sur l'autre bare-metal cesse d'être un trou noir. ★ Trois pièges de lecture : `acl.hujson` est la policy **headscale** (en hibernation), pas celle du SaaS qui est **générée** ; un `dst` par tag ne couvre **pas** les routes de sous-réseau ; et le nom tailnet est figé à l'**inscription**, d'où toute VM en `nerd-nixos`. See [[rke2-rejects-a-loopback-resolv-conf]].
- [★ nnh — second collecteur par bare-metal : PARQUÉ le 2026-09-27, état des lieux fait](nnh-second-collector-per-baremetal.md) — forme retenue : **deux collecteurs symétriques** (le besoin nikopol est le bord WAN du hotspot, local et hors ligne ; celui de bioskop est l'entrant vers l'infra). La **sonde** est déjà agnostique, le **collecteur** est mono-hôte en 7 pièces, et la coupure du cycle de flakes interdit de dériver le blueprint depuis le catalogue ndh → le rendre auto-assertionnant.
- [★★★ cold start 2026-09-27 RÉUSSI + `nikopol-mgmt` déclaré, bloqué sur UN profil incus](cold-start-2026-09-27-nikopol-mgmt.md) — les 2 clusters `Provisioned`, et `nikopol-mgmt` traverse toute la chaîne (`kind: management`, 1 pet, `target: nikopol-nixos`) pour buter sur `node-nikopol-mgmt doesn't exist`. Correctif écrit (rke2lab `70489ed9c`) : les profils suivent les slots **réalisés**, dérivés — plus jamais d'acte hôte pour déclarer un cluster. ★ Il ne manque qu'un **grow sur bioskop**. Porte aussi les faits incus (une image = des FICHIERS root-only, le dataset est le volume déplié) et la convention de VIP.
- [★★★ mesurer la valeur DÉRIVÉE, jamais la supposée — 4 erreurs en une soirée](measure-the-derived-value-not-the-assumed-one.md) — la même forme quatre fois : un outil qui répond « moins » au lieu de « je ne peux pas » (`2>/dev/null` avalant un refus, `zfs list` non privilégié, un grep aveugle, un mauvais projet incus), et une adresse déduite d'un voisin au lieu d'être lue. Le réflexe et les cinq règles.
- [★★ Tailscale Services + bascule `grants` — tranché, RIEN d'implémenté (2026-09-27)](tailscale-services-and-grants-unlock.md) — cible distante **supportée** (la doc dit l'inverse, la source du fork tranche) ; `autoApprovers.services` sinon PENDING à vie ; la bascule `grants` doit `del(.acls)` dans le MÊME changement, sinon l'ancien bloc autorise encore. ★ Un refus d'ACL est un TIMEOUT, jamais `Connection refused` (RST = paquet arrivé) — ça a invalidé ma propre preuve du trou `headless`, qui reste réel mais se lit dans la policy. See [[tailnet-routing-owned-by-hosts-not-pods]].
- [★★ rke2 REFUSE un resolv.conf loopback et redirige TOUT le DNS du cluster vers 8.8.8.8 (corrigé 2026-09-26)](rke2-rejects-a-loopback-resolv-conf.md) — sous systemd-resolved `/etc/resolv.conf` est le stub `127.0.0.53` ; rke2 autogénère alors un fichier Google que CoreDNS forwarde, donc `nixos.<host>` — servi par le seul dnsmasq du bare-metal — était NXDOMAIN **partout**, et CAPN ne pouvait pas joindre son endpoint incus : les deux clusters bloqués 90 min sur ce nom. ★ Discriminateur en une commande : TCP vers l'endpoint **OPEN** depuis le pod, nom NXDOMAIN — comparer ce que résout le NŒUD à ce que résout un POD. Correctif `--resolv-conf` + `IPv6AcceptRA.UseDNS=false` sur les deux liens (rke2lab `ea1da8bd8`, vérifié vivant). See [[incus-placement-must-be-stated-not-inferred]].
- [★★★ cold start bioskop-mgmt RÉUSSI (2026-09-26) + le placement incus doit être DIT](incus-placement-must-be-stated-not-inferred.md) — `bioskop-mgmt-master` RUNNING avec cilium ; bridges UP, 1ers baux v4 **et v6** (à l'adresse v4-embarquée). ⚠️ Sans `target`, incus place au HASARD entre membres à égalité : champ ajouté (seed-incluster `1d8804441` + rke2lab `6fbe87452`), **non livré**. Le `scheduler.instance=manual` de ndh protège aujourd'hui en rendant nikopol IMPLAÇABLE. See [[bridges-leave-incus-forced-not-chosen]] [[ndh-logger-wrapper-disables-errexit]].

- [★ swf-registry (hy-reg) — workspace monté, intégration à trancher](swf-registry-workspace-and-toolchain.md) — registre de *références* vers des configs d'agents épinglées au commit, installables dans 7 harnais. Bare + worktree + env flox (py311/node22) validés le 2026-09-27 ; les 3 voies d'intégration restent à trancher. See [[gh-token-env-shadows-sso-keyring]].

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
| Rules / patterns / gotchas | 25 | [INDEX-rules-gotchas.md](INDEX-rules-gotchas.md) |
| Memory / workspace mechanics | 6 | [INDEX-memory-mechanics.md](INDEX-memory-mechanics.md) |
| Backlogs / misc | 33 | [INDEX-backlogs.md](INDEX-backlogs.md) |
| Chantiers ABOUTIS — sept. 2026 | 8 | [INDEX-shipped-2026-09.md](INDEX-shipped-2026-09.md) |

## Renamed since these notes were written — translate before searching

Checked against `feature/nixos-node-substrate` on 2026-09-19. Memory predating a
rename keeps the old word, so a literal search finds nothing in the code.

- **`seed-master` → `seed-outcluster`** (`17de9ca95`, 2026-10-01, pushed on
  `feature/viewpoint-separation`). 161 files; **zero Java identifiers moved** (the
  package stays `io.seedmatic.rke2lab.controlplane.*`), so only coordinates and prose
  changed. Translate when searching: the Maven selector is now `-pl :seed-outcluster`,
  the flake output `.#seed-outcluster`, the nix binding `seedOutclusterJar`, the jar
  `share/java/seed-outcluster.jar`, the log `.local.d/seed-outcluster.log`. Memory and
  `.claude/` were deliberately NOT swept (a record rewritten to match a later rename
  falsifies it) — `incontainer-test-not-in-seedmaster-reactor.md` keeps the old word in
  its FILENAME, and `docrepo-dag-state.md` carries ~30 historical mentions. ★ Why the
  word changed: the pair did not contrast on one axis — the chain is
  **`operator -> pulumi -> outcluster -> incluster`**, where link 1 is a PERSON (the one
  who runs `pulumi`), so `master` was naming a rank inside a sequence of PLACES. The
  spelling (no internal hyphen) is what kept the BRANCH `seed-incluster` out of scope:
  no ref rename, no flake-input URL move, no lock churn. See [[runmode-livegate-pulumi-abstraction]].
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
  ⚠️ **That rebuild could never be committed, for 10 days.** `.claude/.gitignore`
  carried an UNANCHORED `checkpoint-*.md` (meant for session checkpoints), which also
  matched the memory file whose name starts with the same word. Anchored to
  `/checkpoint-*.md` on 2026-09-29. A name-shaped rule must say WHERE it applies.
- **★ Corrected 2026-09-29 — 7 of those 16 "dead" refs were never dead.**
  The 09-19 audit ran against ONE memory tree while the files sat in the other:
  memory was written into the `main` checkout while work was committed from each
  session's own worktree, so two trees diverged (248 files vs 325). The 3-way merge
  that reconciled them recovered `caprke2-byo-ca-secret-contract`,
  `cold-start-cleanup-and-funnel-cert-persistence`, `flox-carrier-containerize-spike`,
  `flox-controller-build-deploy-state`, `flox-envs-runtime-crd-delivery`,
  `flox-gate-secret-flow-devlxd-then-certmanager` and
  `ghapp-webhook-reconcile-and-funnel-rename`.
  **9 refs remain genuinely dead**, re-verified against the reconciled tree AND the
  hub on 2026-09-29: `realm-boundary-gate` (7×), `spec-figure-first-reading-loop` (4×),
  `c4-diagrams-flowchart-not-native-dsl` (3×), `port-edge-domain-ownership`,
  `thread-manifestations`, `systematic-debugging`, `runtime-view`, `render-config`,
  `pipeline-spec-legibility-cleanup-post-go`. Each is a fact someone meant to write
  and did not; the citing entries still hold the gist.
  Re-run: extract `\[\[name\]\]` from `*.md`, resolve bare names against this dir
  and `hub:` ones against the hub memory dir. Ignore `name`/`x`/`links`/`link` —
  they are syntax examples in the header, not refs. **And run it against ONE
  reconciled tree** — an audit over a forked memory reports false deaths.
