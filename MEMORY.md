# Memory index — rke2lab

Project memory for rke2lab. Cross-cutting facts (profile, conventions, principles)
+ cross-repo chantiers live in the **hub** ([[hub:MEMORY]] at
`/Volumes/git-worktree-store/seedmatic/claude-hub.d/main`), auto-loaded as session root.
★ Moved there on 2026-10-02 with the étage-0 migration — the old
`/private/var/lib/git/nxmatic/claude-hub.d/main` is frozen reference, and the local org
dir disagreed with the remote anyway (`origin` is `seedmatic/claude-hub`, not `nxmatic`).
See [[etage0-bare-worktree-migration]].

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

- [★★★ étage 0 bioskop → bare/worktree — EXÉCUTÉ, vérifié, ancien SUPPRIMÉ le 2026-10-02](etage0-bare-worktree-migration.md) — on travaille depuis `/Volumes/git-{bare,worktree}-store` ; l'ancien n'existe plus (6,4 Go rendus). ★ Le triage inverse a trouvé 3 vivants gitignorés + 8 stash invisibles aux refs. RESTE : rebuild NixOS (opérateur) + mémoire orpheline aux 7 autres dépôts. See [[pulumi-stack-per-worktree-backlog]].

- [★ deux décisions en attente de l'utilisateur (2026-09-20)](unmanaged-mac-ssh-material-chain.md) — (1) passer les auth-keys tailnet en `ephemeral = true` pour supprimer la cause des fantômes qui retiennent les noms [[tailnet-node-identity-ephemeral-ghosts]] ; (2) corriger l'identité `nxmatic`/`/Volumes/user-home` du profil nikopol et **activer nix-darwin en local sur le laptop corp** plutôt qu'écrire un enrôleur — risque : nix-darwin prend la main sur `/etc/ssh` et les shells d'une machine sous MDM.
- [★★★ flox envs — PRÉVU, PAS COMMENCÉ : séparer producteur / livraison / réalisation (décidé 2026-09-23)](references-resolve-at-build-not-runtime.md) — décision utilisateur : **les références se résolvent au BUILD, pas au runtime** ; le lock va dans git et voyage avec le rendu, l'annotation `relock` reste la *commande* du bump mais gagne une **portée par inputs** (tous par défaut). Trois rôles effondrés en un dans flox-controller, et les deux découpages sont DÉJÀ écrits dans ses propres commentaires (`the lock is NEVER produced by the controller`, `until split into a leader-elected cluster manager`) — la régression est `propagateRelock`, qui estampille chaque révision de catalogue en relock total. ⚠️ C'est un DaemonSet sans leader : N pods écrivent le même `status.Lock` pendant que chaque nœud réalise sa closure — masqué par les clusters mono-nœud. Le producteur doit **commiter dans git** (branche `flox-catalogue`, qui ne redéclenche pas le rendu) → plaide pour un pipeline Tekton, le droit d'écriture hors des nœuds. See [[seed-incluster-crd-and-binary-travel-by-two-pins]] [[node-env-gated-oneshots-skip-capn-nodes]].
- [★★★ nikopol-mgmt — modèle B tranché (2026-09-25) + PREMIER cas d'usage du clustermesh](nikopol-mgmt-federation-clustermesh-first-case.md) — bioskop **enfante** nikopol-mgmt puis il s'**auto-adopte** : le plan CAPI n'est qu'un échafaudage, mais le **clustermesh MGMT** (id 1 ↔ 3) est permanent — pré-armé depuis toujours, jamais eu de 2e membre, et son **appariement est out-of-band** (`clustermesh-remote-users` vide, aucun Secret `cilium-clustermesh`). Whiteboard `.claude/claude-preview.adoc`. ★ Incus **EN CLUSTER : RETENU** (rejet retiré le 09-25 — `database-client` garde l'itinérant hors du raft ; corrige la version antérieure de cette ligne). Étape 1 LIVRÉE + vivante : `fabric-br` et la zone appartiennent à NixOS, `nixos.<host>` résout des deux côtés. See [[bridges-leave-incus-forced-not-chosen]] [[single-owner-rule]].
- [★★ ndh NORMALISÉ 2026-10-03 (PR #8)](ndh-normalized-and-flox-envs-vendored.md) — `main` forcé après 20 mois de gel, pile montée, 8 branches → 2 (3 archivées), envs flox vendorés. ⚠️ RESTE : rebuild `nixos.bioskop`, opérateur.
- [★★ flox lock — DÉCIDÉ, PAS COMMENCÉ : flake DU SUBTREE possédé par fleet](flox-env-lock-logic-belongs-to-fleet.md) — ⛔ les 2 copies partent ensemble ; celle de rke2lab est déjà cassée (un seul style de quote).
- [★★ image Tart nerd-nixos — DÉCIDÉ, PAS COMMENCÉ : hors de l'activation darwin](nerd-nixos-image-build-leaves-activation.md) — ~31 GiB/switch, par **interpolation de dérivation**, donc invisible à un grep.
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
| Rules / patterns / gotchas | 54 | [INDEX-rules-gotchas.md](INDEX-rules-gotchas.md) |
| Memory / workspace mechanics | 6 | [INDEX-memory-mechanics.md](INDEX-memory-mechanics.md) |
| Backlogs / misc | 33 | [INDEX-backlogs.md](INDEX-backlogs.md) |
| Chantiers ABOUTIS — sept. 2026 | 8 | [INDEX-shipped-2026-09.md](INDEX-shipped-2026-09.md) |

## Renamed since these notes were written — translate before searching

- [Table de traduction des mots renommés](renamed-words-translate-before-searching.md) — `seed-master`→`seed-outcluster`, `flox-catalogue`→`flox-catalog`, `world-gateway`→`seed-broker-port`, specs re-découpées. ⚠️ Une mémoire antérieure à un renommage garde l'ancien mot.

## Known debt in this memory

- [Dette connue de cette mémoire](memory-index-known-debt.md) — entrées trop longues (201/224), `INDEX-osgi-pipeline` dormant, et l'audit de liens morts dont **7 fausses morts** dues à deux arbres de mémoire divergents.
