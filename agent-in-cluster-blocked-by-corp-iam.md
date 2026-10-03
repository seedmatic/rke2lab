---
name: agent-in-cluster-blocked-by-corp-iam
description: "Un agent Claude Code dans un pod n'a pas d'identifiants Bedrock — la frontière de permissions du compte d'entreprise ferme les deux routes propres (mesuré 2026-10-03)"
metadata:
  node_type: memory
  type: project
  originSessionId: b5348306-a83e-49e8-bb21-406f5e96e83c
  modified: 2026-10-03T11:22:53.930Z
---

Tout plan d'**atelier de dev en cluster** (Eclipse Che, DevWorkspace, DevPod, n'importe
quel pod qui héberge l'agent) bute sur le **même** maillon, et il n'est pas technique.

## Le contrat Bedrock, et le seul morceau qui ne voyage pas

SSOT : `ndh/modules/claude-code-bedrock-env.nix` — 5 variables d'environnement **réelles**
(Claude Code ne lit pas le bloc `env` de `settings.json` pour choisir Bedrock) :
`CLAUDE_CODE_USE_BEDROCK=1`, `AWS_PROFILE=ai-tools-shared`, `AWS_REGION=us-east-1`,
+ 3 ids de modèles. **Quatre voyagent trivialement dans un pod. `AWS_PROFILE` non.**

Parce que ce profil n'est **pas un compte à nous** : c'est le **SSO d'entreprise Hyland**
(`sso_session = hyland`, `identitycenter.amazonaws.com/ssoins-6684b922d5a25f41`,
sso_region `us-east-2`), compte **445316526014**, rôle **`AWSPowerUserAccess`**, identité
`Stephane.Lacoin@hyland.com`. Il marche sur le Mac parce que le **cache SSO** vit dans le
home (`~/.aws/sso/cache/`) et qu'un **navigateur** peut rafraîchir le jeton.
⛔ Dans un pod : ni cache, ni navigateur.

## ★★★ Les deux routes propres sont FERMÉES — mesuré, pas supposé

`aws iam simulate-principal-policy` sur
`arn:aws:iam::445316526014:role/AWSReservedSSO_AWSPowerUserAccess_3785c3e24722364b` :

```text
iam:CreateOpenIDConnectProvider   implicitDeny   ← fédération OIDC du cluster : NON
iam:CreateRole                    implicitDeny
iam:CreateUser                    implicitDeny   ← clés longues par sops : NON
iam:CreateAccessKey               implicitDeny
bedrock:InvokeModel               allowed        ← le seul droit utile
```

Et `aws iam list-open-id-connect-providers` → **liste vide** : aucun provider OIDC
n'existe dans ce compte. Donc ni fédération du cluster (`AssumeRoleWithWebIdentity` +
jeton de compte de service projeté), ni utilisateur IAM à clés longues livrées par sops —
alors que **le dépôt sait déjà faire sops**. Ce n'est pas la technique qui bloque.

⚠️ `implicitDeny` ≠ `explicitDeny` : « non accordé », donc **demandable** à l'équipe cloud
Hyland. Mais c'est une demande de gouvernance, pas un acte à notre main. Et la route OIDC
en demanderait **deux** : le provider IAM *et* un point de découverte OIDC du cluster
**joignable publiquement par STS** — or les clusters sont derrière le tailnet, donc il
faudrait publier le JWKS (S3/CloudFront). Deux demandes, pas une.

## ★★★ Ce qui reste praticable — et c'est MIEUX que je l'avais d'abord écrit

Le **Mac reste l'émetteur** : il a le navigateur et le cache SSO.
`aws configure export-credentials` rend des identifiants **temporaires**
(`ASIA…` + `SessionToken`, `Expiration` ≈1 h, renouvelables tant que le jeton SSO vaut).
Deux routes **documentées et supportées**, pas des bricolages :

1. ★ **`awsCredentialExport`** (réglage de `settings.json`) — fait **exactement** ça :
   Claude Code lance une commande au démarrage **et à chaque rechargement** d'identifiants,
   et lit du JSON (`{"Credentials":{AccessKeyId,SecretAccessKey,SessionToken,Expiration}}`,
   ou le format plat de `export-credentials --format process`). Avec une `Expiration` ISO
   valide, il met en cache jusqu'à **5 min avant** l'échéance. Donc le pod *tire* ses
   identifiants du Mac par le tailnet — **ni Secret à faire tourner, ni cron**.
   ⚠️ La résolution de chaîne a un **timeout de 60 s** (`CLAUDE_CODE_AWS_CHAIN_RESOLVE_TIMEOUT_MS`
   pour l'allonger). Et `awsAuthRefresh` est l'autre réglage, déclenché seulement sur
   identifiants **expirés** — à ne pas confondre.
2. **Passerelle qui signe côté serveur** : `CLAUDE_CODE_USE_MANTLE=1` +
   `CLAUDE_CODE_SKIP_MANTLE_AUTH=1` + `ANTHROPIC_BEDROCK_MANTLE_BASE_URL=…` → les pods
   envoient des requêtes **NON signées**, le relais sur le Mac injecte les identifiants.
   C'est la forme correcte de l'idée « faire sortir le trafic par l'hôte ».
   ★ Mesuré : `bedrock-mantle:CreateInference` et `CountTokens` sont **`allowed`**.
   ⚠️ Mais Mantle exige aussi une **habilitation du compte** par l'équipe AWS, distincte de
   l'IAM — **non testée**. Un 403 qui ne nomme aucune action = compte non habilité.
   ⚠️ Mantle a ses **propres** ids de modèles (`anthropic.claude-sonnet-5`, sans préfixe
   `us.`) — les profils d'inférence `us.anthropic.*` y rendent **400**.

⛔ `ANTHROPIC_BEDROCK_BASE_URL` existe aussi (route Invoke), mais sur ce chemin il n'y a
**pas** de drapeau « ne pas signer » : le client signe toujours en SigV4, donc un relais
devrait re-signer et il faut quand même des identifiants côté pod. ⚠️ Et tout relais doit
réémettre `Content-Type: application/vnd.amazon.eventstream` **intact**, sinon Claude Code
rejette le flux (`Bedrock streaming response has content-type`) ou retombe en non-streaming.

⚠️⚠️ Ne **jamais** contourner par `ANTHROPIC_API_KEY` ni `apiKey` : ça détourne le SDK de
Bedrock, et ça change le **modèle** *et* la **facturation**.

★ Point de gouvernance à ne pas taire : pousser des identifiants **d'entreprise** dans un
cluster de homelab est une décision qui sort du périmètre perso — à poser explicitement.

See [[che-code-without-che]] [[measure-the-derived-value-not-the-assumed-one]]
[[workload-grow-foundations-resume]].
