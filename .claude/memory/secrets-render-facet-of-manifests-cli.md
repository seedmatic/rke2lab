---
name: secrets-render-facet-of-manifests-cli
description: "Design gravé (2026-09-09, PAS codé) : le render des secrets N'EST PAS un nouveau pipeline — c'est la CLI manifests elle-même. Elle rend les secrets ; à l'explode le matériel NODE_BOOTSTRAP est EFFACÉ de la branche et EXTRAIT dans le matériel de bootstrap (Porte 1). Jumeau in-cluster = même CLI (PublishCliScenario) dotée de l'identité de reveal (cellier, recipients seeding→seeded, livrée Porte 1). Rotation ≠ re-grow. + le chemin ESO→REST→webhook→Flux→ça."
metadata:
  node_type: memory
  type: project
---

Design tranché avec l'user (2026-09-09), **PAS codé** — l'user veut graver le design + le chemin qui y a mené, pas coder tout de suite.

## Le render des secrets = la CLI manifests, PAS un nouveau pipeline

La **CLI manifests sait DÉJÀ rendre les secrets** : ils sont synthétisés dans l'arbre comme n'importe quelle ressource. Le seul geste distinctif est à l'**explode** : l'exploder **EFFACE** le matériel de secret (opt-in `ManifestAnnotation.NODE_BOOTSTRAP`, whole-unit `PackageMetadataProfile(...,true)` OU per-resource pour une unité mixte) **HORS de la branche** et l'**EXTRAIT** dans le **matériel de bootstrap** (`.bootstrap/rke2lab-bootstrap.yaml` → `SERVER_MANIFESTS` → cloud-init user-data → oneshot RKE2 `rke2lab-server-manifests` au boot). C'est la machinerie **Porte 1** EXISTANTE, pas à réinventer.

⇒ **La branche git ne porte ZÉRO matériel de secret** — forme la plus stricte de l'invariant (même pas scellé sur la branche ; cf. [[flox-gate-secret-flow-devlxd-then-certmanager]] §INVARIANT).

## Jumeau in-cluster (le vrai manque)

Le render manifests a déjà DEUX incarnations qui **sèment les mêmes coordonnées dans OSGi** : grow-time (seed-master, 1er render) + in-cluster (`PublishCliScenario`). L'incarnation in-cluster est aujourd'hui **secret-blind** (`EphemeralCellar` semé par ghapp seul, `.secrets` sops illisible, pas de seal) → elle **strippe** les secrets. C'est LE trou : le render des secrets n'a qu'une incarnation (grow-time) ; l'in-cluster est délibérément aveugle.

Pour qu'elle devienne le **render-des-secrets in-cluster**, il lui faut l'**identité de reveal**. Elle existe par conception : le cellier scelle recipients **seeding-cluster → seeded-cluster** ([.claude/cellar-secrets-design-handoff.md](.claude/cellar-secrets-design-handoff.md)) → **le cluster semé DÉTIENT l'identité qui révèle ce qui a été scellé POUR lui**. L'architecture a anticipé ce render. L'identité (clé age / identité cellier) est livrée UNE fois Porte 1 (la clé age est déjà sur la liste immuable NODE_BOOTSTRAP). Dotée d'elle, la MÊME CLI in-cluster rend les secrets peuplés + explode comme au grow.

## Distinction d'avec Flux+sops (forme ÉCARTÉE comme cible)

Claude avait proposé « matériel **scellé SUR la branche**, Flux dé-scelle au reconcile ». **L'user tranche l'AUTRE forme** : rien sur la branche, tout extrait à l'explode dans le matériel de bootstrap. Flux+sops reste le **cas dégénéré / raccourci** si on veut la rotation-par-commit AVANT de coder le reveal in-cluster — mais ce n'est PAS la cible : il garde du matériel (même scellé) sur la branche ET déporte le déchiffrement HORS du pipeline OSGi.

## Application : chemin choisi PAR incarnation (fork tranché par l'user)

Roter un secret = **re-jouer le render (la CLI)**, PAS re-grow l'instance. **L'user tranche (a)** : l'incarnation in-cluster **applique les nouvelles valeurs directement dans le namespace `rke2lab-secrets`** du cluster vivant (server-side apply) → le replicator fan-out. Elle ne passe PAS par le matériel de bootstrap (inutile : le cluster est vivant, l'API est là).

⇒ **principe unifiant** : le render produit le matériel de secret ; l'**application est OUT-OF-BAND** (jamais sur la branche réconciliée par Flux → l'invariant tient dans les deux cas), par un chemin choisi selon le cycle de vie de la cible :
- **grow-time** : la cible boote (pas d'API encore) → explode → matériel de bootstrap → oneshot RKE2 **au boot**.
- **in-cluster** : la cible est vivante → **server-side apply direct dans `rke2lab-secrets`** → replicator.

(Rotation de l'ANCRE — clé age / CA racine — reste un acte à part, rare, proche d'un re-grow : on ne rote pas in-cluster l'identité qui sert à révéler.)

## Correction user (2026-09-09) — la synthèse révèle TOUJOURS du cellier ; PAS de lecture directe de `.secrets`

Rectif d'une **erreur de Claude** (le « sous-fork A/B » — faux, supprimé) : la **synthèse manifests ne lit JAMAIS `.secrets` directement** — elle **révèle du cellier** (`reveal*` → `EphemeralCellar` / cases). `.secrets` n'est lu que par les **scions de seal** qui **peuplent** le cellier. Donc le cellier est **INCONTOURNABLE** : c'est la source secrète de la synthèse, host comme in-cluster. Il n'existe pas de chemin « la synthèse lit `.secrets` ».

**Chemin unique (une seule forme, host = in-cluster) :** `.secrets` → (seal) → **cellier** → (reveal) synthèse → explode/apply.

**Les DEUX delta in-cluster** (vs host) :
1. **`.secrets` pas smudgé** — host-side le filtre git smudge/sops déchiffre au checkout (plain read, [.claude/cellar-secrets-design-handoff.md](.claude/cellar-secrets-design-handoff.md) le note). In-cluster : pas de filtre → blob chiffré → le **seal** doit **déchiffrer en-process** avant de lire : **`SopsDecryptor`** (existe, « GARDÉ ») + **clé age livrée Porte 1**.
2. **cellier éphémère** — host-side le cellier durable (state Pulumi) est peuplé une fois au grow ; in-cluster `EphemeralCellar` part **vide** (semé par ghapp seul aujourd'hui) → pour nourrir la synthèse il faut **RE-JOUER le seal in-cluster** (`.secrets` décrypté → cellier). Le durable ne voyage PAS jusqu'au cluster (= *cause* du secret-blind).

**Seal minté vs seal-ingest (distinction critique).** NE PAS re-jouer in-cluster les seals qui **mintent** (CA cluster-pki, clés → matériel FRAIS/divergent = ancre cassée). Ne re-jouer que les seals **d'ingestion** (lecture idempotente d'une valeur d'app depuis `.secrets` : floxhub-token, tailscale oauth, flux webhook-token, PaC…). Le matériel minté reste **grow-time durable** (rotation de l'ancre = acte à part, rare).

**Comment peupler le cellier éphémère in-cluster = LE vrai fork (remplace le faux A/B) :**
- **re-seal-depuis-`.secrets`-décrypté** → rotation in-cluster possible ; exige `.secrets` lisible in-cluster ⟹ cluster **reveal-capable** ⟹ **MGMT-only** (le mgmt racine voit déjà tout ; **INTERDIT** à un workload feuille = lui donner toute la flotte).
- **livré-pré-scellé** (seal reste host-side au grow, on livre l'entrée déjà scellée dans le cellier éphémère, à la ghapp) → **pas de rotation in-cluster** (roter = re-sceller/re-livrer host-side), mais scopable.

**Résolution par les rôles :** **mgmt** = re-seal-depuis-`.secrets` (rotation in-cluster ; il voit tout de toute façon) → apply `rke2lab-secrets` → replicator + source ESO. **workload** = feuille, ne touche JAMAIS `.secrets` ; sa rotation = le mgmt met à jour son `rke2lab-secrets`, **ESO tire la tranche** (ESO = livraison scopée par feuille).

Sous-point plomberie : l'incarnation in-cluster (mgmt) doit **fetcher la source portant `.secrets`** (chiffré), pas seulement la branche `manifests/<cluster>` rendue.

## Le chemin (comment on est arrivé là)

Départ : workloads = ESO tire du mgmt (feuilles, provider kubernetes). Question user : fournir les secrets AU mgmt de la même manière, depuis seed-master qui le grow ?

1. **Idée : service REST dans seed-master + provider ESO custom.** ÉCARTÉ : (a) seed-master = grower one-shot ; un ESO qui poll (`refreshInterval`) exigerait un endpoint ALWAYS-ON joignable = le « médiateur permanent » déjà refusé (secret-tiers L19, ESC abandonné pour la même raison) ; (b) chicken-and-egg non supprimé, juste déplacé (le credential ESO→seed-master doit être planté Porte 1 en amont).
2. **Webhook generator ESO** (external-secrets.io/latest/api/generator/webhook) : **tue** l'objection provider-Go (CRD + HTTP templaté, auth = Secret k8s référencé). MAIS #1+#2 **tiennent** (la page confirme : poll `refreshInterval` = always-on ; auth Secret à pré-planter).
3. **Insight décisif : le mgmt EST la racine → pas de besoin de refresh.** ESO sert à tenir un MIROIR en phase (workloads = clusters séparés). La racine n'a pas de miroir : plantée une fois, elle vit dans etcd. Faire tirer le mgmt = seed-master en **proxy permanent devant un fichier git** (`.secrets`) = pire (ordering cold-start dégradé, always-on). Le webhook ne gagne QUE pour du **dérivé/dynamique minté in-cluster** (= Porte 2, ex `GithubWriterTokenMintEdge`), pointé sur ce minteur ou une SoT externe, JAMAIS sur seed-master-le-grower.
4. **User : « j'avais oublié Flux ».** Le vrai manque n'est pas un secret manager, c'est un **render des secrets in-cluster, jumeau du render des manifests**.
5. **Convergence :** ce render existe DÉJÀ dans la CLI manifests. Il suffit de dé-aveugler l'incarnation in-cluster (identité de reveal) ; l'explode-vers-matériel-de-bootstrap est le mécanisme, pas à réinventer.

Sous-question « quel secret manager sans cloud » → **A.** le cluster mgmt lui-même (ESO kubernetes provider, déjà là, pour le fan-out feuilles) ; **B.** Flux+sops+age (SoT = `.secrets`, clé age Porte 1) = le raccourci ; **C.** OpenBao SEULEMENT si secret dynamique/TTL (lease/PKI/audit — sinon YAGNI). Le **cellier** = secret store maison mais **grow-time**, pas runtime (ne pas confondre avec l'ingress).

See [[secret-tiers-eso-model]] [[flox-gate-secret-flow-devlxd-then-certmanager]] [[manifests-publish-in-cluster-render]].
