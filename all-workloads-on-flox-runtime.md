---
name: all-workloads-on-flox-runtime
description: "★ PRINCIPE (user, 2026-09-11) : TOUS nos workload-containers doivent tourner sur le flox runtime — image prodImage()/debugImage() + annotation flox.seedmatic.io/environment.<c> ou nix-build.<c> (le NRI injecte flox/nix). Pas d'images stock (alpine/debian/busybox) pour NOS containers. Les images vendor tierces (kube-vip, envoy, replicator, gtm, cert-manager) restent telles quelles."
metadata: 
  node_type: memory
  type: project
  originSessionId: 57ab666d-7ffd-4083-b5a4-7b41b85cdd6a
  modified: 2026-09-11T05:55:46.561Z
---

**Principe posé par l'user (2026-09-11) :** « tous nos containers doivent être basés sur le flox runtime … tous nos workloads doivent tourner dans le flox runtime. »

**Ce que « sur le flox runtime » veut dire :**
- image = le flox runtime : `ManifestSynthesisContext.current().floxDebugPolicy().prodImage()` (ou `.debugImage()`, var locale `floxImage` ; flox-controller = `floxControllerImage()`), PAS une image stock ;
- ET/OU annotation pod `flox.seedmatic.io/environment.<container>` (injecte les packages d'un FloxEnv, activation-based) ou `flox.seedmatic.io/nix-build.<container>=<pvc>` (runtime nix + overlay /nix sur PVC) — le plugin flox-NRI wire le runtime.

**Scope :** NOS containers (on écrit leur `command`/`args`/`script`). Les images VENDOR tierces (kube-vip, envoy-gateway, mittwald replicator, github-token-manager, cert-manager, openebs…) sont déployées telles quelles → hors scope.

**Déclencheur :** le render pipeline (`RenderPipelineManifestsUnit`) — step `clone` sur `alpine/git:2.45.2` et `step-render` sur `debian:stable-slim` = VIOLATEURS. Le clone alpine checkoute `.secrets`+l'asset RAW (pas de filtre git-sops) → d'où le hack « re-smudge » dans render-publish. Corollaire : c'est le même nœud que le blocker smudge ([[in-cluster-render-smudge-cellar-asset-blocker]]) — le filtre `sops-yaml` n'est jamais wiré in-cluster.

**Plan (aligné au principe, EN COURS) :**
1. **Fix filtre = dans le flox runtime** : baker `git config --system include.path = ${git-sops-filter}/sops` dans l'image runtime (ndh) → filtre wiré pour TOUT container flox-runtime, zéro hook par-step. (Alternative plus légère écartée : hook par-env git-sops, ne suit pas le principe.) `SOPS_AGE_KEY` reste fourni par-pod (secret sops-age répliqué).
2. **Refonte des 3 steps render** : `clone` alpine→prodImage + `SOPS_AGE_KEY` (smudge à la source) ; `step-render` debian→prodImage ; **supprimer le re-smudge de rattrapage** (le clone smudge, le render worktree hérite du .git/config). ⚠️ vérifier l'interaction base flox-runtime × overlay nix-build (/nix du node reste flox donc flox dispo).
3. **AUDIT transverse** (lancé 2026-09-11) : identifier TOUS les containers pas sur flox runtime (init containers, Jobs, sidecars, steps Tekton — faciles à rater). Résultat = catalogue VIOLATORS à convertir.

À terme, ce principe mérite d'être gravé dans CLAUDE.md / docs (convention). See [[in-cluster-render-smudge-cellar-asset-blocker]] [[in-cluster-render-secret-full-via-git-sops-floxenv]] [[manifests-publish-in-cluster-render]].
