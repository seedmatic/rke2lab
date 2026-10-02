---
name: rke2lab-canonical-maven-invocation
description: "Convention rke2lab (2026-09-20, donnée par l'utilisateur) — la build canonique est `./mvnw clean verify -Pall-worlds,claude -Dmaven.build.cache.skipCache=false -DskipTests=true` ; le profil `claude` redirige le build vers target~claude pour ne pas piétiner le target/ de l'utilisateur"
metadata:
  node_type: memory
  type: feedback
  originSessionId: 0b18b1f3-3eda-496a-865d-1fbc722b0d30
  modified: 2026-10-02T09:50:39.471Z
---

```
flox activate -- ./mvnw clean verify -Pall-worlds,claude \
  -Dmaven.build.cache.skipCache=false -DskipTests=true
```

**Pourquoi chaque morceau compte :**

- **`-Pclaude`** — **une voie de sortie par acteur**, et c'est la raison la plus importante de ne pas
  improviser une autre invocation. `build-parent/pom.xml` déclare deux profils jumeaux :
  `nxmatic` → `target~nxmatic` (l.1113-1117, celui que l'utilisateur emploie) et `claude` →
  `target~claude` (l.1122-1124), le commentaire l.1118 disant explicitement « the twin of nxmatic
  for the agent's builds … so agent runs never [collide] ». Donc `-Pclaude` est **ma** voie ; bâtir
  sans lui écrit dans `target/`, qui n'est la voie de personne.
- **`-Pall-worlds`** — vide `surefire.excludedGroups` (le défaut exclut `live | spike`), donc
  réintègre tous les tags. `exec/seed-master/pom.xml:743` note que « ce projet se construit
  **toujours** avec `-Pall-worlds,claude` », et que le profil `bench` s'active par **l'absence** de
  `skipBench` et non par `activeByDefault`, parce qu'un `activeByDefault` est désactivé dès qu'un
  `-P` quelconque est passé.
- **`verify`**, pas `compile` — la vérification réelle passe par les phases de packaging/bundle
  (OSGi), donc `compile` ne prouve presque rien.
- **`-DskipTests=true`** — cohérent avec le défaut du repo ; pour exécuter réellement, `-DskipTests=false`.

⚠️ **`CLAUDE.md` dit autre chose** : « un build de module utilise toujours `-am` :
`./mvnw -pl :seed-master -am …` », et ne mentionne pas `-Pclaude`. Les deux se combinent (un `-pl …
-am` reste valable pour cibler, mais il faut y ajouter `-Pall-worlds,claude`) — j'ai fait l'erreur
de lancer `-pl :incus-ingress -am compile` tout court, ce qui (a) ne vérifie pas grand-chose et
(b) écrit dans le `target/` de l'utilisateur.

## Récidive le 2026-09-25 — et le symptôme qui doit me le rappeler

J'ai de nouveau lancé `flox activate -- ./mvnw package` **sans profil**, alors que cette note
existait. Résultat : **tous** les modules compilent `SUCCESS`, et la porte `osgi-staging` de
`seed-master` échoue seule :

```
[realm-wiring-integrity] the assembled uber-jar must boot with every bundle resolved and the
flat/bundle export sets disjoint: [the assembled framework failed to boot:
java.lang.reflect.InvocationTargetException]
```

Le `InvocationTargetException` n'est PAS déballé par `StagingExecutionStrategy` (l.376 met juste
`ex` dans le message), donc `-e` ne donne rien de plus que la pile Maven — ne pas perdre de temps
à la chercher là.

★ **Heuristique** : un échec de boot du framework assemblé **alors que la compilation passe
partout** doit d'abord faire suspecter *le profil manquant*, pas mon diff. Deux mécanismes
concourent : (a) le `target/` partagé peut livrer des classes compilées par l'IDE, et (b) sans
aucun `-P`, les profils `activeByDefault` sont actifs — or le pom de seed-master s'appuie
explicitement sur le fait qu'un `-P` les désactive (le profil `bench` s'arme par l'*absence* de
`skipBench` pour cette raison même). Donc bâtir sans profil ne produit pas seulement un autre
répertoire : ça produit une **autre configuration de build**.

Et `package` ne suffit pas — c'est `verify` qui exerce la porte dans sa forme attendue.

## Amendement 2026-10-02 — `-DskipBench`, et pourquoi `-Pall-worlds` est un geste OPÉRATEUR

Deux ajouts décidés avec l'utilisateur en vérifiant `main` après le réalignement git-flow
([[gitflow-realign-pr-per-increment]]) :

- **`-DskipBench`** — `osgi/runtime/bench/bench-tests` fait échouer la construction sur
  `PaxLoggingJulCaptureTest.jdkJavaUtilLoggingIsCapturedByPaxAndNeverHitsTheConsole` : le puits
  logback reste **vide** (`(file: )`), rien n'est drainé du bus `java.util.logging`. L'utilisateur
  le dit *flaky* ; mesuré, il échoue **2/2 à l'identique** sur ce siège — donc reproductible ici,
  pas intermittent. Décision : **ignoré par défaut**, réparé dans une session dédiée.
  ★ Conséquence qui compte plus que le test : sans ce drapeau, `exec/seed-outcluster` est
  **`SKIPPED`** derrière l'échec, donc *jamais vérifié*.
  ⚠️ Le drapeau seul est invisible — l'ignorance doit devenir un **marquage déclaré dans le code**
  (incrément `fix/`), sinon c'est le smell « importé, `enable = false`, jamais signalé ».
- **`-Pall-worlds` ne doit PAS être lancé par moi avec `-DskipTests=false`** : il vide
  `surefire.excludedGroups`, dont le défaut exclut `live | spike`, donc il exécuterait les tests
  `live` qui touchent le système provisionné. C'est un geste **opérateur**. Pour une barrière de
  revue, prendre `-Pclaude` seul : il désactive les `activeByDefault` comme le fait
  `-Pall-worlds,claude`, sans réarmer les groupes `live`.

Donc la barrière de revue (il n'y a **aucune CI**, cf. [[gitflow-realign-pr-per-increment]]) est :

```bash
LOG="$TMPDIR/rke2lab-verify.log"   # cf. [[command-output-to-stable-file-not-tail]]
flox activate -- ./mvnw clean verify -Pclaude -DskipBench \
  -Dmaven.build.cache.skipCache=false -DskipTests=false -B > "$LOG" 2>&1
echo "MVN_EXIT=$?" >> "$LOG"       # sinon le code rapporté est celui du dernier echo
grep -E 'BUILD (SUCCESS|FAILURE)' "$LOG"
```

See [[common-d-is-a-directory-wide-nix-input]].
