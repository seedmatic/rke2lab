---
name: cellar-extended-resource-producer
description: "Le renversement (brainstorm 2026-07-14, GRAVÉ dans les specs): le cellier n'est pas qu'un store post-récolte — sur la réalisation Pulumi, Cellar.store PRODUIT la resource. L'ancien ResourceManager de main s'y dissout. Tout scion contribue, pas seulement le docteur."
metadata:
  type: project
---

Brainstorm 2026-07-14 (parti de la brique incus du top-down), CONVERGÉ et gravé dans
`host-cellar-realisation-spec.adoc` (§ every-scion-contributes), `atlas/seed.adoc` (Diagram R + le NOTE
output-gate) et `seed-gardening-lexicon.adoc` (entrée fruit). Le renversement qui débloque la logique
d'exécution manquante ([[master-execution-stage-missing-state]]).

**Le renversement.** `ResourceManager` (main, supprimé avec l'étage d'exécution) ne se re-crée PAS. Ce qu'il
faisait — `ResourceCreationPipeline` bâtissant par scion un « thin graph mirror + arête dependsOn »
(SystemdAdapterResource, SeedImageBuildResource…) — EST littéralement ce que `PulumiCellar.store` fait déjà :
un `up()` hors-run enregistre une `CellarEntry` (`ComponentResource`, `registerOutputs` sous la coordinate).
Donc **ranger au cellier = produire la resource Pulumi**. `ResourceManager` se DISSOUT dans `Cellar.store`.

**Tout scion contribue** (pas seulement le docteur, qui n'était que le 1er consommateur). Le SCION, in-container,
récolte ET range LUI-MÊME : le host pose le `Cellar` dans Felix au boot (`registerService(Cellar.class)`, fait
au GIVEN de ClusterSeedScenario), le scion le résout via `ScenarioRegistry.require(Cellar.class)` comme il
résout son contact/RunGate, et `store` sa récolte. **PAS de retour de récolte au host** (pas de champ récolte
sur `RunbookEnvelope` — l'aller-retour serait absurde). Le `RunbookEnvelope` garde `(runbook, consultations)` :
les consultations traversent car le host les GREFFE dans le runbook (narration) ; la récolte ne traverse pas,
sa destination est le cellier, pas le runbook (deux destinations, deux canaux — ne pas confondre, c'est le
piège que le canal consultations du docteur invite).

**Parcelle courante = FAIT AMBIANT, à CÔTÉ du cellier, pas dedans.** Le run cultive UNE parcelle (le master
stack) ; le host la publie dans Felix comme le `RunGate` (twin). Le `Cellar` reste NEUTRE et adressé du dehors
(`store(Parcel, …)`) — surtout PAS un cellier mono-parcelle ni une sentinelle `CURRENT` qu'il résoudrait
lui-même : le docteur adresse N parcelles (une par patient), ça le casserait. Le scion résout les deux (Cellar +
Parcel ambiante) et `store(parcel, harvest)`. Idée sentinelle écartée : elle donnerait un état caché au cellier.

**Faits grounded (relus, pas de mémoire):**
- `PulumiCellar` EST le backend Pulumi : `store`=`up()`+`registerOutputs`, `fetch`=walk historique→enveloppe
  par output natif (`PulumiCellar.java:92-221`).
- Les env-vars du nœud (`env|grep RKE2LAB` sur le master vivant, 11/11 `*_DIR`) sont des constantes du
  **catalogue conteneur** (`/srv/host/...`), JAMAIS une racine host de `BootstrapPaths`. Le SOIL = uniquement
  le plot d'écriture, pas une source d'env-var.
- Les racines host ne nourrissent que le **mount gRPC** (`seedInstanceDevices`, ~14 `.disk`), qui reste host.
- Racines mortes sur main: `stateRoot` MORTE, `clusterNodeRoot` mkdir-only, `systemdLibexec`/`share`
  mount-vide, `git` mount-seul (agent-tracé, 17 racines).

**Décisions tranchées (à ne pas re-litiger):**
- *dependsOn = CAUSAL* (option C): l'arête se lit du graphe d'AMENDEMENTS (« j'ai eu besoin de l'amendement X
  pour pousser »), pas de la position dans le runbook (A, positionnel), jamais déclarée par le scion (B,
  rouvre le couplage). A et C convergent ; C est la lecture juste.
- *deux natures Pulumi*: RESOURCES (graphe, identité + dependsOn) vs OUTPUTS top-level (`ctx.export`). Les
  outputs sont une PROJECTION des resources (l'ancien `OutputBuilder` republiait les URN/summaries). Le
  top-level = **façade de lecture host**, twin de `Cellar.fetch`, SANS métier (reste host comme le mount gRPC,
  c'est le commanditaire qui la lit).
- *le SCION décide de la valeur d'output, pas la façade* (une façade qui « cueille le fingerprint » = couplage
  métier host, écarté). Le scion marque par rôle neutre existant: `@Scion(Role.FRUIT)`=conservable,
  `SOWING`=attentes internes. La façade hisse mécaniquement ce qui est FRUIT, sans nommer `seedImageFingerprint`.
- *le SOL ne va jamais au cellier* (input dérivable intra-run → PUSH au sow) ; le cellier ne reçoit que des
  outputs produits conservables. Le pull-cellier pour le sol = ÉCARTÉ.
- *le cellier CONSULTE la RunGate et s'adapte* (c'est un outil du système, comme tous). DEUX espaces : gate
  ouverte → `stack.up()` = CONSERVÉ (resources réelles) ; gate fermée → `stack.preview()` = PRÉ-RÉSERVE (le plan
  de CE run). Le scion garde UN seul verbe `store(Parcel, harvest)`, ne choisit pas le mode — la dualité vit
  dans le PulumiCellar. `preview()` ne viole PAS « gate fermée = on n'utilise pas l'outil » : il ne mute rien,
  n'invente rien, c'est l'outil réel qui calcule le plan (= arpenter). La pré-réserve est ÉPHÉMÈRE (plan du run,
  jamais un historique inter-run, sinon cache-qui-ment). Donc dry-run ne PERD PAS la récolte : elle est dans la
  pré-réserve, rendue PENDING dans le runbook (E9).
- *le runbook seul NE porte PAS la récolte* : jGiven `ReportModel` capture les ARGUMENTS de step (`@Quoted`),
  pas le `@Provided/@ExpectedScenarioState`. Donc la récolte en scenario-state n'est PAS récupérable du runbook.
  Deux canaux : runbook = narration/plan ; cellier = donnée (conservée ou pré-réservée). Ne pas confondre.
- *une récolte se FETCH au cellier, jamais poussée en input/amendement* (règle 2026-07-14). Le SOIL/amendement
  porte le *où* (un chemin) ou une facette de policy (bool), JAMAIS une valeur cultivée (fingerprint, urn,
  checksum). Un scion qui a besoin de la récolte d'un autre fait `Cellar.fetch`. Le piège vestige :
  `ManifestSynthesisRequest.imageState` (porte `imageFingerprint`, récolte Stage-A) n'est PAS reliée à
  `ManifestsRunbookInput` dans le monde scion (défaut `ImageState.unknown()`) — elle doit le RESTER : quand la
  synthèse a besoin du fingerprint, manifests le FETCH au cellier (récolte du scion image), incus ne le
  transporte pas. Donc au fil CONSULT, incus forwarde le SOIL (le plot), jamais le fingerprint. L'ordre causal
  (dependsOn) le rend sain : image cultivée+rangée AVANT la synthèse qui la fetch.

**Granularité — (a) maintenant, (b) évolution PLANIFIÉE (pas oubliettes):**
- (a) grain gros: le FRUIT entier est l'output (`stack output <domain>.fruit`→blob), rôle existant, rien à
  inventer. C'est le choix courant.
- (b) grain fin: le scion marque AU CHAMP quels champs sont des scalaires plats top-level (retrouve
  `seedImageFingerprint="abc"` comme string) — enrichit le vocabulaire du split (facette `flat`/rôle dédié).
  **On tombera dessus DANS le top-down** quand un `stack output X` plat concret sera exigé. À garder au radar.

See [[master-execution-stage-missing-state]] [[options-always-as-c4-diagrams]] [[controlplane-to-osgi-migration-frame]].
