---
name: value-object-and-mapper-disciplines
description: "★ FEEDBACK (2026-09-09, corrigé ~6× en une session) : disciplines value-object + mapper. Pas de null/Optional pour du mandatory (fail-loud explicite), records TOUJOURS complets (valider au ctor), pas de static pour du comportement, mapper partagé = SeedCodec pour NOTRE json. Étend [[instance-methods-not-static-helpers]]."
metadata:
  node_type: memory
  type: feedback
---

Réaffirmé lourdement pendant le chantier incus-identity (fondation 5) — l'user a corrigé chacun de ces points, souvent plusieurs fois. **Why :** ce sont des réflexes que je n'applique pas spontanément ; les graver évite le ping-pong. Étend [[instance-methods-not-static-helpers]] (#1).

**1. Pas de `static` pour du COMPORTEMENT** → méthode d'instance sur le pojo qui porte l'état (le Stage jGiven). Restent statiques : leaf-utils PURS génériques (`base64`, un transform sans état), factories (`X.of`), et la **validation appelée depuis un ctor compact** (un record ne peut pas appeler une méthode d'instance à la construction). « si trop complexe → membre délégué ». Les reflectors (`index`/`roleValues`) restent statiques (leaf-utils + uniformité stricte inter-reflectors).

**2. Pas de `null` à la frontière** → `Optional` OU fail-loud. NullAway REJETTE `new Record(null)` (`passing @Nullable where @NonNull required`) et `Optional.ofNullable(map.get())` (voir le témoin `Optional.<T>ofNullable` dans [[formatter-strips-imports-added-before-usage]]).

**3. MANDATORY ≠ Optional.** `Optional<X>` dit « peut être absent » — ne PAS l'utiliser pour un requis. Un input obligatoire (ex. l'identité incus, sans quoi on ne peut pas grow) = **champ non-Optional + exception explicite** si absent/incomplet. **Aucun fallback, aucun skip silencieux** : « la seule chose qu'on peut faire c'est lever une exception explicite ». (Contraste : le replicator-secrets EST optionnel → skip légitime. Bien distinguer selon que la chose est indispensable.)

**4. Un record est TOUJOURS complet.** Valider dans le ctor compact (`requireNonNull` + rejet des blanks → throw), pas de coalesce-vers-"" ni de `isComplete()` a posteriori : un instance incomplet ne doit pas pouvoir exister (« no instances with incomplete state »). Le fail-loud vit alors au ctor, au décodage.

**5. Mapper partagé = `SeedCodec` (pas `new ObjectMapper()`) pour NOTRE json.** Le vrai enjeu = la CONFIG du mapper (WireEnumModule + datatypes + FAIL_ON_UNKNOWN off) ; `SeedCodec.decode(String)→JsonNode` / `decode(String,Class)` / `encode`. NOTRE json (`.secrets`, config, wire) = **must** codec. Le json **EXTERNE** (API GitHub `application/vnd.github+json`, réponses HTTP) = un autre problème, un mapper brut/séparé y reste **légitime** (le codec-wire n'a pas à s'y appliquer). L'hôte pur-JDK peut aussi utiliser le codec (seed-master a `seed-broker-codec`). `createObjectNode`/pretty-writer manquants au codec = pas cher à exposer si un site NÔTRE en a besoin. Sweep partiel fait (bdd .secrets → codec) ; edges/CLI externes laissés (légitimes). See [[caprke2-byo-ca-secret-contract]].
