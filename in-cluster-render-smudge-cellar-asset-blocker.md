---
name: in-cluster-render-smudge-cellar-asset-blocker
description: "★ WIN (2026-09-11) : le render in-cluster SECRET-FULL marche de bout en bout (PipelineRun Succeeded, 214 resources + 8 node-bootstrap = Secrets smudgés, ff-push manifests/bioskop-mgmt). Débloqué par 2 fixes : (1) token render lu RAW depuis git-provider-token de PaC (le 401 était un token VIDE dû au trou sed du carrier minimal, PAS un souci de scope ; controller/nri-plugin sont PUBLICS) ; (2) fix B = builds flox-controller dans un scope cgroup-HÔTE (systemd-run --scope via nsenter) car nsenter n'entre PAS dans le cgroup → les builds node-side étaient comptés dans le cgroup 2Gi du pod → OOM-loop sur les envs mesh."
metadata: 
  node_type: memory
  type: project
  originSessionId: 57ab666d-7ffd-4083-b5a4-7b41b85cdd6a
  modified: 2026-09-11T13:07:56.039Z
---

**★ FIX SHIPPÉ (2026-09-11), pas encore grow-validé.** Root cause confirmé = le filtre `sops-yaml` n'était JAMAIS wiré in-cluster (le FloxEnv git-sops installait le paquet mais ne posait pas l'`include.path` ; l'opérateur le fait via home-manager `sops.nix:15`). Fix poussé (design tranché avec l'user, « tous nos workloads sur le flox runtime ») :
- **flox-controller `2cd369f`** : NOUVEAU `FloxEnv spec.inject` (`[{name, value|secretKeyRef}]`) — le webhook `PodFloxMutator` GET le CR de l'env qu'un container annote (`environment.<c>`) et upsert son `spec.inject` sur le container (secretKeyRef résolu par kubelet dans le ns consommateur ; `FloxEnvNamespace`=ns controller). Complète le modèle capability (packages + FLOX_FLOXHUB_TOKEN + inject).
- **rke2lab `152103f2c`** : (1) FloxEnv `git-sops` gagne un `[hook.on-activate]` = `git config --global include.path=$FLOX_ENV/sops` (wire le filtre GLOBAL → source checkout ET render worktree smudgent) ; (2) son `spec.inject` = `SOPS_AGE_KEY` depuis `sops-age` (optional) → les consommateurs l'ont juste en annotant l'env ; (3) render pipeline : clone(git-fetch) + step-render sur `prodImage` + `environment.step-clone/step-render=toolchains/git-sops` → le clone smudge `.secrets` au checkout, **re-smudge workaround + SOPS_AGE_KEY hand-wired SUPPRIMÉS** ; check fail-loud (SOPS_AGE_KEY + filtre enregistré) ; scripts en text-blocks `: "..."` sous xtrace (token gardé `set +x`) ; (4) EnvoyGateway installer → prodImage + `environment.installer=kube/base`.
- **docs `ce5722ba4`** : pattern flox-store-resolved + pac-in-cluster-render-spec + atlas/manifests + README (principe runtime universel + spec.inject + Mermaid C4).
- ⚠️ **`$FLOX_ENV/sops`** : le hook PARIE que flox linke `$out/sops`+`$out/sops.d` du paquet dans `$FLOX_ENV` — À CONFIRMER au grow (le check fail-loud « filter not registered » le dira). Fallback si non-linké = bin self-config dans le paquet ndh git-sops-filter.
- **RESTE** : rebuild deployable nxmatic (`-Dinclude=io.seedmatic.rke2lab:ndh-contract` ; le lock flox-controller→2cd369f apporte l'image webhook + le CRD spec.inject) → grow → valider (git-sops realize avec hook, render clone/step reçoivent SOPS_AGE_KEY, asset smudge → plus de ENC). Tests flox-controller = PAS runnables dans le sandbox (« signal: killed » = limite exec/mémoire ; build+vet verts, tournent chez l'user/CI). See [[all-workloads-on-flox-runtime]].

--- HISTORIQUE (diagnostic 2026-09-10) ---

## Ce qui est SHIPPED aujourd'hui (tout poussé, verify claude vert)
- **gtm token durable (modèle B fichier + relocalisation)** : rke2lab `c038f6593` — gtm `ClusterToken` minte `github-token` dans **rke2lab-secrets** (`spec.secret.annotations` = mittwald `replication-allowed`+`allowed-namespaces=rke2lab-system`) ; `FloxControllerManifestsUnit` ajoute un stub `github-token` replicate-from + monte le Secret en **VOLUME/fichier** (`GITHUB_ACCESS_TOKEN_FILE=/var/run/flox/github-token/token`), plus d'env secretKeyRef. flox-controller `7babe50` : `exec.go floxCommandEnv` lit le token du **fichier** (frais/invocation → gère l'arrivée tardive ET la rotation 45m, sans restart). Lock rke2lab `d4b0e8954`.
- **Annotation relock** (flox-controller `7babe50`) : `flox.seedmatic.io/relock=<val>` ≠ `status.relockToken` → le reconciler drop `status.Lock` → re-lock frais. CRD régénéré (`relockToken`). FloxCatalog : pas besoin (re-dérive à chaque reconcile, suit Flux). Usage : `kubectl annotate floxenv <n> flox.seedmatic.io/relock="$(date +%s)" --overwrite`.
- **Cleanup print-build-logs** : setting nix INVALIDE (warning « unknown setting », c'est le flag CLI `-L`). Retiré de `floxenv.NixConfig()` (flox-controller `98a5c36`, render+pods) ET du DS controller (rke2lab `c000ef0ad`). Lock → `98a5c36`.
- **Migration consumers (B)** : rke2lab `3b999c494` — les **12** containers qui wrappaient `flox activate --dir /root --` explicitement l'ont perdu (le plugin NRI auto-wrappe via `flox.seedmatic.io/environment.<c>`). Sites : Headscale ×6 (837/931/bootstrap/gateway/tailscale-client/wait-for-headscale), Headplane ×2 (sync/serve), Kdns, Funnel backup+restore, TailnetPurge. **2 must-stay** : Headscale bootstrap.sh `hs()` (re-activation `kubectl exec` dans un AUTRE container, bypass entrypoint) + Headplane `hp_healthcheck` (commande de **probe** exec, non réécrite par l'auto-wrap). Skip-log plugin `b058dd2` (flox-runtime).

## VALIDÉ live ce soir
- gtm marche (App `READY`, ClusterToken → `github-token` minté, `ghs_…`). Après un `rollout restart ds/flox-controller` (pod avait démarré AVANT le mint), **tous les FloxEnv mesh se sont réalisés** → chaîne gtm→token→nix→claude-hub prouvée. (Le fix fichier B rend le restart inutile au prochain grow.)
- Le **step-render tourne in-cluster** et BUILD manifests-cli (reactor) → **auto-wrap + PATH-merge validés** (cas composé environment+nix-build).

## LE BLOCKER — smudge in-cluster
`render-manifests-bl46x` FAILED. Log step-render :
```
Failed to decode payload into CellarAsset  (ScenarioCellar.importSealed:337)
Caused by: JsonParseException: Unrecognized token 'ENC'
```
`rehydrateInClusterAsset` (ManifestSynthesisScenario:631, AVANT les reveals) lit `IN_CLUSTER_ASSET_FILE=.secret-in-cluster-cellar.yml` (:701) via `smudgeFromHead` dans le **render worktree** (`.local.d/render/<cluster>`), mais le blob est resté **ENC[...]** → le filtre sops-yaml n'a pas décodé.

**Faits :** secret `sops-age` présent rke2lab-system (clé `age.agekey`, source flux-system) ; `SOPS_AGE_KEY` câblé optional depuis lui (RenderPipeline:270). Le re-smudge du step (`RenderPipelineManifestsUnit:343-349`) ne couvre que `.secrets`+`.ndh-ssh.d/keys.yaml` (workspace SOURCE), pas l'asset. L'asset est lu AVANT le reveal de `.secrets` → on n'a PAS la preuve que `.secrets` aurait smudgé in-cluster.

**Insight distinctif :** le render **OPÉRATEUR** (grow) smudge l'asset SANS PROBLÈME (round-trip validé antérieurement) ; seul l'**in-cluster** casse → cause in-cluster-spécifique.

**2 pistes à départager demain :**
- **(A) `flox activate` de l'auto-wrap (NOUVEAU ce soir) ne propage pas `SOPS_AGE_KEY`** au command wrappé (= la piste user « on a pas la variable d'env »). Vérifier : le render step est maintenant `flox activate --dir $HOME -- nix run …` ; est-ce que SOPS_AGE_KEY (et les autres env du pod) survivent dans l'env du command ? Test rapide : ajouter un `echo "AGEKEY_LEN=${#SOPS_AGE_KEY}"` au début du step, ou vérifier si `flox activate` scrub l'env. SI c'est ça → le fix touche le plugin (préserver l'env pod à travers l'auto-wrap) ou le step (ré-exporter).
- **(B) filtre `sops-yaml` pas enregistré dans le render worktree** (`.local.d/render/<cluster>` = git DIFFÉRENT du workspace source où `.secrets` smudge). Vérifier : comment le FloxEnv `toolchains/git-sops` enregistre le filtre (git config --global vs per-repo ? hook d'activation ?) et si `smudgeFromHead`/`GitCli` dans le worktree render a `filter.sops-yaml.smudge`. SI c'est ça → enregistrer le filtre dans le worktree render (ou globalement).

**Prochain grow** déploie aussi : fix token fichier (plus de restart), gtm→rke2lab-secrets+réplication, relock, skip-log. ⚠️ heads-up déjà donné : quand Flux applique le nouveau DS controller (env→fichier) sur le cluster courant AVANT le prochain grow, l'ancien binaire (image `0.0.0-develop` pré-7babe50) lit ni l'env retiré ni le fichier → transient token loss (mesh réalisé persiste via gcroots) → grow pour clore la fenêtre.

See [[in-cluster-cellar-asset-reach]] [[in-cluster-render-secret-full-via-git-sops-floxenv]] [[flox-controller-github-token-via-gtm]] [[manifests-publish-in-cluster-render]].
