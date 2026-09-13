# Plan — rke2-adoption-controller : baked OCI → FloxEnv runtime

**But** (user, 2026-09-12) : sortir le `rke2-adoption-controller` de l'image nixos du node-base ; le livrer en **FloxEnv** (carrier `flox-carrier` + binaire depuis un env flox) + annotations flox-controller. Aligné sur [[all-workloads-on-flox-runtime]] et la règle [[single-owner-per-manifest]].

## État actuel (à défaire)
- `nixos/rke2-adoption-controller.nix` : build le binaire ET une image OCI `io.seedmatic.rke2-adoption-controller:0.0.0-develop`, bakée au node-base (`flake.nix`, `nixos/default.nix`), air-importée par rke2 (`IfNotPresent`).
- `Rke2AdoptionControllerManifestsUnit` : Deployment `image = IMAGE` bakée, `args [...]`. Le CRD est stagé séparément depuis le flake (`stage-rke2-adoption-controller-crd`) → **garder** ce staging.

## Modèle cible (mimer kdns / FloxEnvManifestsUnit)
- Carrier : `image = floxDebugPolicy().prodImage()` = `rke2lab/flox-carrier:0.1.0` (minimal).
- Binaire : vient d'un **FloxEnv** `spec.manifest.install` qui installe l'output flox-catalogue du controller.
- Sélection au pod : annotation `flox.seedmatic.io/environment.controller = "<folder>/rke2-adoption-controller"` (+ HOME/UID/GID) ; le flox-NRI met le binaire sur PATH ; le flox-controller webhook gate le scheduling (mute les **Pods**, pas le Deployment Flux-owned → règle single-owner respectée).

## Pièces (ordre)
1. **[✅ DONE — commit `e17479f18` sur `flox-catalogue`, poussé]** publier le controller comme output de la catalogue → `floxcatalog:catalogue#rke2-adoption-controller` + env `environment.d/cluster-api/rke2-adoption-controller` défini+locké. **Worktree existant** : `/private/var/lib/git/seedmatic/rke2lab.d/flox-catalogue` (tip `7712fd2a6`). **Concret** dans son `flake.nix` : (a) ajouter l'input `rke2-adoption-controller.url = "github:seedmatic/rke2lab/rke2-adoption-controller"` (+ `inputs.nixpkgs/flake-utils/flake-commons.follows`), EXACTEMENT comme le fait déjà le flake feature (`flake.nix:81-84`) ; (b) ré-exporter son **binaire** dans `packages.<sys>.rke2-adoption-controller` (PAS le `-image` — le carrier flox n'a pas besoin de l'OCI ; cf. re-export feature `flake.nix:549-555` qui prend `adoptPkgs.rke2-adoption-controller`) ; (c) `nix flake update` + `./lock-envs.sh` pour régénérer `flake.lock` (+ un `environment.d/cluster-api/rke2-adoption-controller/manifest.toml`+`.lock` en miroir, optionnel car `FloxEnvManifestsUnit` rend le manifest inline). Le controller flake (branche orphelin) build DÉJÀ `packages.<sys>.rke2-adoption-controller` (binaire) ET `-image` (OCI). Isolation : la branche est checkout ailleurs → confirmer qu'aucune autre session n'y travaille avant d'éditer.
2. **FloxEnvFolder** : ajouter une catégorie (ex. `CLUSTER_API` → `"cluster-api"`) ou réutiliser une existante (TOOLCHAINS/NETWORKING/MESH). → `units/runtime/flox/FloxEnvFolder.java`.
3. **FloxEnvManifestsUnit** : `createEnv(scope, resolver, "rke2-adoption-controller", FloxEnvFolder.CLUSTER_API, adoptionControllerManifest())` — install = `flakeRef("rke2-adoption-controller")` + `bash`/`coreutils` (+ `kubectl` si le controller shell-out ? non, c'est un binaire kubebuilder pur → juste le flake + shell minimal pour `flox activate`). Prod-only (pas de flavor debug au départ).
4. **Rke2AdoptionControllerManifestsUnit** : `image → prodImage()` ; `command → ["manager"]` (binaire kubebuilder par défaut) en gardant `args` ; annotations pod `FloxAnnotation.ENVIRONMENT.forContainer("controller")="cluster-api/rke2-adoption-controller"` + HOME(`/root` ?)/UID/GID ; volumes emptyDir `flox-config` (`/.config/flox`) + `flox-cache` (`/.cache/flox`) + mounts ; **supprimer** la constante `IMAGE`. Le conteneur s'appelle déjà `controller`.
5. **Retirer le baking** : `nixos/rke2-adoption-controller.nix` (l'emballage OCI + l'entrée node-base dans `flake.nix`/`nixos/default.nix`) — garder le build Go + le staging CRD. Vérifier que `FluxServiceKustomizationPlanner` route bien la cell adoption-controller sur l'edge flox-runtime (annotation `environment.<c>` détectée → gate scheduling).
6. **Build + validation** : `flox activate -- ./mvnw -pl :manifests-core -am -Dmaven.build.cache.skipCache=true clean compile` ; puis grow/live : le controller doit démarrer sur le carrier avec le binaire injecté par flox.

## Contexte lié
- Le controller devient AUSSI l'owner de l'egress ([[cp-endpoint-reach-tailnet-headscale-migration]] pièce 4) + du patch kubeconfig (pièce 3) — code Go dans la branche orphelin `rke2-adoption-controller`. Le présent plan ne concerne QUE la LIVRAISON (packaging), pas le code Go du reconciler.
- NEXT après packaging : coder le reconciler (self-adoption `kubernetes.default.svc` d'abord, puis egress).
