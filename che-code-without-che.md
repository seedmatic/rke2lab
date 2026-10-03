---
name: che-code-without-che
description: "L'éditeur che-code (VS Code OSS, arm64) s'injecte par initContainer donc ne dépend pas de Che — et l'extension Claude Code officielle est sur Open VSX (mesuré 2026-10-03)"
metadata:
  node_type: memory
  type: project
  originSessionId: b5348306-a83e-49e8-bb21-406f5e96e83c
  modified: 2026-10-03T11:18:09.905Z
---

Exploration d'Eclipse Che pour monter des ateliers dans les clusters de workload,
2026-10-03. Verdict : **l'éditeur et l'agent sont acquis, Che lui-même est de trop.**

## Les mesures qui tranchent

- ⛔ **Che est ABSENT du dépôt** : 0 occurrence de `eclipse-che|devworkspace|che-code|
  checluster` hors `.flox-envs.d`. Le « tout est déjà là » parlait du **substrat**
  (clusters, machinerie de manifestes, envs flox en cluster), pas de Che.
- **`che-theia` est archivé** (dernier push **2023-04-04**) — et l'utilisateur a de toute
  façon **descopé Theia** le 2026-10-03, motif : la conversation avec l'agent ne convient
  pas. Sujet clos, ne pas le repayer.
- ★ **`che-code` est l'éditeur vivant** (`che-incubator/che-code`, poussé le 2026-10-03) :
  `displayName: VS Code - Open Source`, et ses attributs d'arch incluent **arm64** — ce qui
  compte pour des VM incus sur Apple Silicon.
- ★★★ **L'extension Claude Code OFFICIELLE est sur Open VSX** : `Anthropic.claude-code`
  v2.1.288, ~54,7 M de téléchargements. Donc **pas besoin** de l'adaptateur `claude-code-acp`
  qu'on avait installé pendant l'essai Theia, et pas de mur « marketplace Microsoft ».
  C'est **exactement** le critère qui avait tué Theia, et il est résolu nativement.

## ★★ Pourquoi Che n'est pas la bonne enveloppe (mais che-code l'est)

`che-code-latest.yaml` livre l'éditeur par un **initContainer injecteur**
(`quay.io/che-incubator/che-code:latest`, composant `che-code-injector`) qui dépose
l'éditeur dans un volume partagé ; le conteneur d'exécution est un image quelconque
(par défaut `quay.io/devfile/universal-developer-image:latest`).
→ **che-code s'injecte dans n'importe quel pod. Il ne dépend pas de Che.**

Alors que le spec `CheCluster` traîne une plateforme **multi-locataire** : fournisseur
d'identité **Keycloak** (`identityProviderRealm`, `identityProviderPostgresPassword`…),
**PostgreSQL** (`chePostgresDb`, `externalDb`…), une **gateway** à sidecars OAuth +
kube-rbac-proxy, et un domaine **wildcard** avec TLS. Pour **un seul développeur**, c'est
le vrai coût, et il est porté par *Che*, pas par l'éditeur.

Route légère : **DevWorkspace Operator seul** (`devfile/devworkspace-operator`, vivant,
poussé le 2026-10-02) + che-code — ou même un simple Deployment. ⚠️ Non mesuré : que DWO
s'installe effectivement sans l'opérateur Che. À instruire avant d'y croire.

## ⛔ Le bloqueur de premier rang est ailleurs

L'agent en pod n'a **pas** d'identifiants Bedrock, et c'est fermé par la frontière de
permissions du compte d'entreprise → [[agent-in-cluster-blocked-by-corp-iam]]. Tant que ce
maillon n'est pas instruit, un atelier en cluster est un éditeur **sans agent**.

## Deux à-côtés mesurés

- **Kroki/Mermaid** : **400** blocs `[mermaid]` dans `docs/` (le handoff disait 395 — ça a
  dérivé), **aucun** bloc `[kroki]`, et les `:kroki-server-url:` sont **commentés** → sans
  serveur local les diagrammes partent vers **`kroki.io` public**. Un Kroki local est déjà
  documenté (`docs/guides/diagram-preview-kroki.adoc`, contexte Docker `nerd-nixos`,
  `.dev/kroki`) ; **en cluster ça devient un Service**, donc plus facile que sur le Mac,
  et structurant (du source de diagramme d'infra privée vers un service public).
- ⚠️ **Toujours prendre le contexte `-vip`** de la kubeconfig. Les contextes sans suffixe
  (`bioskop-mgmt` → `172.16.1.3:6443`) répondent **401** par drift de CA — l'API server est
  vivant et le certificat n'est **pas** expiré (`notAfter = Nov 18 2036`,
  `CN=rke2lab-admin, O=system:masters`). J'ai d'abord conclu « cible injoignable » sur ce
  mauvais contexte : **c'était faux**. Avec `-vip`, tout répond.

## ★★ Le substrat est VRAIMENT là — et il allège Che plus que prévu

Mesuré le 2026-10-03 sur `bioskop-wrkld-vip` (`Ready` depuis 41 h, k8s `v1.34.10+rke2r1`,
NixOS 26.05) :

- **arm64**, **8 vCPU**, **32 GiB** allouables — de quoi héberger un pod d'atelier, et
  che-code publie bien de l'arm64. ⚠️ Mais **un seul nœud control-plane, aucun worker** :
  l'atelier tourne sur le control-plane.
- `openebs-zfs` (défaut) + **`openebs-zfs-persist` en `Retain`** → workspace persistant
  immédiat, pas de question de stockage.
- ★★★ **IngressClass `tailscale`** (`tailscale.com/ts-ingress`), à côté de `cilium` →
  un atelier s'expose **sur le tailnet** avec son propre TLS. Ça **dissout** le prérequis
  le plus lourd de Che (domaine **wildcard** public + cert-manager), et ça rejoint
  [[tailnet-routing-owned-by-hosts-not-pods]].
- `flux-system` + `tekton-pipelines`/`tekton-operator` déjà actifs → la livraison existe.
- Pas de namespace cert-manager — inutile si l'ingress tailscale porte les certs.

See [[workload-grow-foundations-resume]] [[renamed-words-translate-before-searching]].
