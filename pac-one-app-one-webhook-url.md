---
name: pac-one-app-one-webhook-url
description: "★★ Une GitHub App n'a QU'UNE url de webhook, donc le PaC d'un sous-plan ne reçoit jamais rien (mesuré 2026-09-30). L'asymétrie Flux/PaC — accélérateur vs seul déclencheur — et le réconciliateur retenu."
metadata:
  node_type: memory
  type: project
---

**Mesuré le 2026-09-30.** `nikopol-mgmt` n'avait aucun `PipelineRun` alors que GitHub rapportait une
livraison réussie, et tous ses composants étaient sains — c'est ce qui rendait le diagnostic
trompeur.

## Ce qui trompe

La livraison réussie visait **`nikopol-mgmt-flux-webhook`**, le receveur **Flux**. Un receveur Flux
réconcilie un `GitRepository` ; il ne crée jamais de `PipelineRun`. Bonne porte pour Flux, mauvaise
pour un rendu. Les deux portes d'un cluster sont `<cluster>-pipelines-webhook` et
`<cluster>-flux-webhook` (`Funnel.hostname()` = `<cluster>-<leaf>`, `FunnelLeaf` n'a que ces deux
feuilles).

## La cause, structurelle

**Une GitHub App n'a QU'UNE url de webhook**, et `GithubAppCli` la construit comme
`PacWebhookFunnel(DEFAULT_CLUSTER, DEFAULT_TAILNET)` — le funnel de la RACINE, par construction. Les
4 webhooks de dépôt existants visaient tous un `*-flux-webhook` et étaient posés **à la main**. Le
funnel PaC du sous-plan était provisionné, certificaté, et câblé à rien. Son contrôleur PaC était
silencieux depuis son démarrage.

## ★★ L'asymétrie à retenir

| Porte | Rôle du webhook | Si l'event est perdu |
|---|---|---|
| receveur Flux | **accélérateur** — le moteur est `spec.interval` (mesuré `10m`) | ≤ un intervalle de latence. **Rien n'est perdu** |
| PaC | **seul** déclencheur, aucun poll | le rendu **n'a jamais lieu**, définitivement |

⚠️ Et **GitHub ne réessaie pas** une livraison échouée (d'où le bouton *Redeliver*). Pointer un
webhook sur un hôte itinérant perd donc des rendus en silence, avec risque de désactivation du hook.

## Ce qui a été retenu

Le **parent réconcilie la fraîcheur de rendu de ses sous-plans** : désiré = branche de l'enfant rendue
à la tête de la source ; observé = `source.sha` + `dirty` enregistrés dans son `manifest.yaml` ;
action = POST vers son funnel PaC. **Déclencheur = la tête de la source qui change**, donc rien à
relayer : pas de serveur, pas de seconde porte publique, pas de file. Sans état — la boucle EST la
durabilité. Portée = `ownedBy(self)` filtré sur MGMT. Vit dans `seed-incluster`, qui EST déjà cette
relation (`PoolReflection` = le parent écrit dans la branche de son enfant).

Supprime : les 4 hooks manuels, `RepoWebhookConfig` + `GithubRepoWebhookConfigurer` + son edge (écrits,
jamais appelés), et `administration=write`.

Gravé dans `docs/architecture/cluster-api/pac-in-cluster-render-spec.adoc` § `webhook-delivery`
(rke2lab `fec07de85`). Pas encore construit.
