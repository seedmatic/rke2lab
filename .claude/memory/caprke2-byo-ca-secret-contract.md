---
name: caprke2-byo-ca-secret-contract
description: "Contrat BYO-CA de CAPRKE2 v0.25.2 (source: pkg/secret/certificates.go) — les 4 secrets CA pré-créés par nom pour un workload : <cluster>-{ca,cca,etcd,peer-etcd}, type cluster.x-k8s.io/secret (PAS kubernetes.io/tls), clés tls.crt/tls.key. Vérifié pour C2."
metadata:
  node_type: memory
  type: reference
---

Vérifié depuis l'upstream faisant autorité `rancher/cluster-api-provider-rke2@v0.25.2` `pkg/secret/certificates.go` (`NewCertificatesForInitialControlPlane`, `asSecret`, `Name`, `Lookup`/`secretToKeyPair`). C'est le contrat que C2 doit produire pour donner une **CA déterministe** (enracinée mammoth-skate) à un cluster workload greenfield par CAPRKE2. Voir [[secret-tiers-eso-model]] (bootstrap Porte 1) et le plan workload-grow (C2).

**Nommage** : `Name(cluster, purpose)` = `<cluster>-<purpose>`. `<cluster>` = le nom de la **ressource CAPI `Cluster`** (bare `<host>-<role>`, ex. `bioskop-wrkld`), namespace = celui du Cluster = `rke2lab-<cluster>`.

**Les 4 CA du control-plane initial** (purpose → fichier rke2 `server/tls/` → notre clé bundle `ClusterCaGenerator`) :
- `<cluster>-ca`        `ClusterCA`("ca")        → `server-ca.crt/key`       ← `server-ca`
- `<cluster>-cca`       `ClientClusterCA`("cca") → `client-ca.crt/key`       ← `client-ca`
- `<cluster>-etcd`      `EtcdServerCA`("etcd")   → `etcd/server-ca.crt/key`  ← `etcd-server-ca`
- `<cluster>-peer-etcd` `EtcdCA`("peer-etcd")    → `etcd/peer-ca.crt/key`    ← `etcd-peer-ca`

(request-header-ca + service.key ne font PAS partie du contrat 4-secrets ; générés par CAPRKE2.)

**Forme du secret** (`asSecret`) :
- **Type = `cluster.x-k8s.io/secret`** (`clusterv1.ClusterSecretType`) — **PAS `kubernetes.io/tls`** (piège : le template `ClusterIssuerManifestsUnit` fait `kubernetes.io/tls`, ne PAS le copier tel quel pour ça).
- Label `cluster.x-k8s.io/cluster-name: <cluster>` (`clusterv1.ClusterNameLabel`).
- Data : `tls.crt` (= cert, contenu écrit verbatim dans `server/tls/*-ca.crt`) + `tls.key` (= clé privée CA). Mettre les valeurs EXACTES du bundle `ClusterCaGenerator` (le `*-ca.crt` = chaîne complète feuille+intermédiaire+root, identique au fichier du nœud mgmt prouvé live).

**IMPLÉMENTÉ (C2, 2026-09-09) — code-complet + BUILD SUCCESS (lane claude, skipCache), grow-validation PENDING.** 14 fichiers / 6 modules : minting cluster-pki (`Amendment.WORKLOAD_TARGETS`, `ClusterPkiCoordinate.WORKLOAD_CLUSTER_CAS`, `WorkloadClusterCas` record scellé, `ClusterPkiSealInput` input+amendment, `ClusterSeal.sealWorkloadCas` additif modèle-frère, `ClusterPkiSealScenario` input+store SEALED, `ClusterPkiRunbookHandler` décode, `ClusterPkiSealAmendReflector` neuf, pom +seed-broker-codec) ; hôte (`ClusterSeedScenario` extrait les noms du facet manifests + sème l'amendment ; `SeedRun.facet`→`Optional<String>`) ; reveal+render manifests (`WorkloadClusterCasMaterial` mirror, slice `ManifestSynthesisRequest`, `revealWorkloadCas`+slug `ManifestSynthesisScenario`, accessor `ManifestSynthesisContext`, `ClusterApiWorkloadManifestsUnit` rend les 4 secrets sur lane NODE_BOOTSTRAP, nb-namespace partagé) ; doc §workload-BYO-CA dans deterministic-cluster-access.adoc. **RESTE à valider AU GROW (opé user) :** binding runtime de l'amendment `List<String>` via `AmendmentBinder`, décodage structurel nested `WorkloadClusterCas`→`WorkloadClusterCasMaterial`, pickup réel CAPRKE2. Voir [[formatter-strips-imports-added-before-usage]].

**Découverte = par NOM, zéro champ sur `RKE2ControlPlane`** : `LookupOrGenerate` → `Lookup` fait un `Get` par nom (le Type n'est PAS vérifié au lookup, seulement name+data), `secretToKeyPair` lit `tls.crt`/`tls.key` ; si présent → `Generate()` saute (KeyPair non-nul) → BYO-CA. Un secret pré-créé (sans ownerReference) est traité comme external ; CAPRKE2 ne le régénère pas et ne le GC pas avec le CP.

**Topologie CA (tranché C2, modèle FRÈRE)** : chaque cluster workload a sa PROPRE hiérarchie CA enracinée DIRECTEMENT sur `mammoth-skate` (frère du mgmt, clés indépendantes → meilleure isolation), mintée au grow mgmt via `ClusterCaGenerator.generate(mammothCert, mammothKey, ts)` par cluster — PAS enfant de la CA mgmt (rejeté : ajoute profondeur, exige la clé CA mgmt au point de signature, et signer des CAs rke2 avec la cluster-issuer-ca low-privilege casserait la frontière du doc deterministic-cluster-access).
