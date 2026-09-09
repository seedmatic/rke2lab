---
name: secret-tiers-eso-model
description: "Modèle 3-couches pour amener les secrets dans les clusters (design tranché 2026-09-09, PAS codé) : bootstrap (Porte 1) → ingress (ESO pour workloads, .secrets pour mgmt) → fan-out (replicator mittwald). + rename rke2lab-replicator-source → rke2lab-secrets."
metadata:
  node_type: memory
  type: project
---

Design tranché avec l'user (2026-09-09), **PAS codé** — le tier des secrets d'APP (steady-state), distinct du bootstrap. Fait suite au chantier canal cloud-init (voir [[flox-gate-secret-flow-devlxd-then-certmanager]] = la Porte 1 bootstrap qu'on vient de shipper).

**Insight clé (correction user) : ESO et le replicator sont DEUX logiques distinctes, complémentaires — ESO ne remplace PAS le replicator.**
- **ESO = INGRESS** : apporte les secrets de l'EXTÉRIEUR dans le cluster (atterrissent dans `rke2lab-secrets`).
- **Replicator (mittwald) = FAN-OUT** : réplique DANS le cluster, de `rke2lab-secrets` → les namespaces consommateurs. GARDÉ.
- `rke2lab-secrets` = la **charnière** (ESO y atterrit / le replicator en source).

**Modèle 3 couches :**
1. **Bootstrap (Porte 1)** — CA, join, kubeconfig, **+ le credential ESO du workload** (SA token/kubeconfig + CA mgmt pour joindre le mgmt). Chicken-and-egg → cloud-init/node-bootstrap depuis `.secrets`. (Shippé pour le mgmt : commits `871ac540e`+`5f21d32dd`.)
2. **Ingress** :
   - **mgmt = RACINE — source = `.secrets` (sops dans git), livrée au grow par le seal (Porte 1 / NODE_BOOTSTRAP).** **Pulumi ESC ABANDONNÉ (2026-09-09, tranché user) :** ESC est un service **Pulumi Cloud** (app.pulumi.com) ; or on tourne sur des **stacks LOCALES** (backend self-managed, pas de compte cloud) → pas d'ESC, pas de provider `external`, pas de médiateur exposé. Donc pas d'ingress runtime pour le mgmt : `.secrets` reste l'**unique root-of-truth** de la flotte. **Rotation mgmt = re-seal ciblé** (une op dédiée qui re-minte + re-livre depuis `.secrets`), PAS un re-grow complet ni un endpoint permanent — c'est le seal qui joue les deux moments d'injection (grow + rotation).
   - **workload = FEUILLE** : ESO (provider **Kubernetes**, 100% local, aucun cloud) → `rke2lab-secrets` du **mgmt** → `rke2lab-secrets` du workload.
3. **Fan-out** : replicator mittwald, `rke2lab-secrets` → namespaces, DANS chaque cluster. Gardé.

Asymétrie **justifiée** : mgmt=racine (semée depuis `.secrets`), workloads=feuilles (ESO tire de la racine) ; replicator fan-out des deux côtés.

**Plomberie workload→mgmt (à coder) :** côté mgmt = un `ServiceAccount`+RBAC (read `rke2lab-secrets`) rendu pour l'ESO du workload ; côté workload = ce token/kubeconfig + CA mgmt livrés Porte 1.

**RENAME couplé : namespace `rke2lab-replicator-source` → `rke2lab-secrets`.** Justif : le namespace n'est plus « la source du replicator » seul — 2 logiques le touchent (ESO in / replicator out) → nommer la charnière neutrement. **PAS `rke2lab-vault`** (« vault » nommerait la source EXTERNE d'ESO, pas la charnière in-cluster ; et sur-vend un HashiCorp Vault qu'on ne déploie pas). Blast-radius : ~5 unités réf. `rke2lab-replicator-source/<secret>` (FloxController/floxhub-token, Tailscale+TailnetPurge/operator-oauth, FluxReceiver/webhook-token, PacSecret), `ReplicatorManifestsUnit` (le namespace L100-113 + annotations mittwald), **`.secrets` `replicateTo` (user, sops) + migration ns live**, couche Java `ReplicatorSourceSecretsMaterial`/`replicatorSources()`/`ReplicatorSecretsSealScenario` (naming seal — renommer aussi ou laisser).

**Note :** ESO PEUT techniquement fan-out direct (un `ExternalSecret` par ns → replicator optionnel), mais l'user garde le replicator comme unique charnière de fan-out (choix valable).

**Sequencing : DOWNSTREAM du provisioning workload** — ESO ne sert que quand un cluster workload EXISTE (donc après étape 5 airGapped + 2b render `-wrkld` + C2 CA workload). Donc : le tier app-secret (ESO + rename) s'implémente à la **phase app-stack workload**, pas maintenant. Design capturé ici. See [[flox-gate-secret-flow-devlxd-then-certmanager]] [[workload-grow-foundations-resume]] [[flux-per-service-kustomizations]].
