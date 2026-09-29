---
name: master-execution-stage-missing-state
description: "★ CONSTAT 2026-07-13 (brainstorm): l'étage host d'EXÉCUTION du provisioning master a été SUPPRIMÉ de seed-master, pas juste débranché. ClusterSeedScenario n'a repris que la NARRATION (sow-and-graft), pas le corps. 6 des 10 packages controlplane/ absents (resources/incus/pipeline/bbox/readiness/systemd), dont ResourceManager + IncusResourceBootstrap ~3490l + BootstrapPaths. Le scénario n'a AUCUN état de provisioning (BootstrapPaths: roots + vue host/staging vs node) → l'outdir du scion manifests n'a pas d'origine, il écrit dans un temp dir. Ligne de récupération (tranchée par l'user): seul le graphe joué en gRPC reste host (~3 classes sur 17 touchent com.pulumi); TOUT le reste peut être joué OSGi-side. Chantier = migration top-down du pipeline de provisioning. En cours d'abord: un pas borné (links→contributor)."
metadata:
  node_type: memory
  type: project
---

**LE CONSTAT (découvert en brainstorm 2026-07-13, en voulant terminer le scénario BDD manifests).**

Le scénario `manifests-bdd` synthétise (prouvé vert, commit `b222fe991`) mais **ne matérialise pas
l'arbre** de master. En cherchant pourquoi, on a remonté un manque structurel bien plus large.

**Ce qui existe sur `main` et a DISPARU chez nous** — `git ls-tree origin/main` sur
`exec/seed-master/src/main/java/io/seedmatic/rke2lab/controlplane/`:
- packages PRÉSENTS chez nous (4): `controlplane`, `/bdd` (le scénario), `/config`, `/policy`.
- packages ABSENTS chez nous (6): `/resources` (ResourceManager, ResourceCreationPipeline,
  Seed*Resource), `/incus` (17 classes dont **IncusResourceBootstrap ~3490 l.** + **BootstrapPaths**),
  `/pipeline` (+`/stages`: ApplicationPipeline, BootstrapPipeline, EnvironmentStage, IncusStage…),
  `/bbox`, `/readiness`, `/systemd`.

`ClusterSeedScenario` a la MÊME ossature que `BootstrapPipeline` de main
(preflight→bbox→incus→systemd→…) mais **n'a repris que la narration** (sow-and-graft vers des scions
qui VÉRIFIENT), **pas le corps** (les Resources Pulumi + la matérialisation qui FAISAIENT le travail).
C'est une coquille narrative.

**Le trou précis qui bloque manifests:** main portait **`BootstrapPaths`** (classe imbriquée dans
IncusResourceBootstrap) — l'état de provisioning propagé à travers tous les stages: roots
(`worktreeRoot`/`assetsRoot`/`manifestsRoot`/`runtimeEnvConfigRoot`/`scriptsRoot`/`systemdRoot`) +
**trois vues** du même layout: `asHostView(NIXOS)`, `asStagingView(stagingRoot)`, locale (DARWIN).
Sur main, `synthesizeAndExplodeManifests` matérialise à `.local.d/bioskop/master/host` (vue STAGING),
puis c'est copié dans le guest (`/srv/host/...`, vue NODE). Nos scénarios n'ont **aucun** de ça:
- pas d'outdir pour le scion → il écrit dans `Files.createTempDirectory` (aléatoire).
- `NodeEnvContext` n'a QUE la vue node (`/srv/host/...`), pas la vue host/staging.
- « l'outdir vient du provisioned state » (intention user) est IMPOSSIBLE tant que ce state n'existe
  pas dans le scénario.

**La chaîne complète de main (à reproduire), `IncusResourceBootstrap.synthesizeAndExplodeManifests`:**
temp scratch → `wipeExplodedLayers(manifestsRoot)` → **synthesize** (écrit le consolidé multi-doc
`manifests.yaml`) → **explode** (LIT le consolidé, split en arbre `<domain>/<package>/<order>-<kind>-<name>.yml`
sous manifestsRoot) → copy systemd units → checksum+summary. **`.explode()` n'est appelé NULLE PART en
live chez nous** (que dans un test) — l'exploder est orphelin de trigger, comme la synthèse l'était.
Les deux étapes s'enchaînent directement, la couture = le fichier consolidé (un `Path`, pas un stream).

**LA LIGNE DE PARTAGE (tranchée par l'user, corrige les "Families" du doc provisioning-slice):**
« la seule logique qui ne peut pas passer OSGi-side, c'est la partie jouée en gRPC. » Vérifié: sur les
17 classes du package `incus/`, **3 seulement** touchent `com.pulumi.*` (IncusResourceBootstrap=17
imports mais surface étroite = déclaration du graphe Instance/Network/Profile/Project ;
PulumiIncusImageProvider=12 ; IncusProviderContext=4). Les **14 autres = 0 Pulumi** (métier pur:
synthesize/explode/checksum/paths/targets/metadata). Donc: graphe gRPC → HOST obligatoire (le moteur
Pulumi résout, un scion n'a pas de moteur autour — cf doc "Decision settled shape A") ; **tout le reste
→ scion**. Corrige même le doc: TargetChecksumPipeline/TargetReloadPolicy (0 Pulumi) sont scion-side
entiers, pas "straddle"; seul le `replaceOnChanges` (attribut de l'Instance) reste host.

**LE CHANTIER = migration top-down du pipeline de provisioning du master node.** manifests-cli-sower
et le dé-mixage ControlplanePolicy en sont des SOUS-problèmes; on ne peut pas les finir sans l'étage
d'exécution. NE PAS repartir bottom-up sur manifests-cli avant ça.

**PAS BORNÉ EN COURS (avant le top-down, "on commit et on voit"):** migrer les
`RKE2LAB_POLICY_LINK_*` du mécanisme OVERLAY (`NodeEnvOverlayService.writeControlplaneOverlay` +
seedVariables, une EXCEPTION au modèle) vers le **NodeEnvContributorRegistry** — modèle uniforme.
Décisions prises: (a) `NodeEnvContext` porte la facette `ManifestsRunbookInput` ENTIÈRE (champ
`facet()`), DefaultNodeEnvContext la reçoit, fin des `new DefaultNodeEnvContext()` internes
(propagation, sinon incomplete-state). (b) contributor **CENTRAL** adossé au **catalog** (pas
par-domaine): un `NodeEnvContributor` unique lit la facette + `ManifestDomainCatalog.stageALinkableDomains`
et émet toutes les `RKE2LAB_POLICY_LINK_<domain>_ENABLED`. Par-domaine était le principe voulu MAIS
trou de couverture: la LinkFacet (7: gitops/networking/clusterApi/storage/mesh/highAvailability/cicd)
≠ les linkables du catalog (6: HIGH_AVAILABILITY/NETWORKING/STORAGE/MESH/CLUSTER_API/PLATFORM), et
mesh/clusterApi n'ont pas de contributor, platform absent de LinkFacet → le central adossé au catalog
(single source of truth) couvre tout. Puis supprimer NodeEnvOverlayService+Default+writeControlplaneOverlay,
retirer `the_controlplane_overlay_is_written` du scénario, le Then vérifie les links dans l'env-config
agrégé. **explode + BootstrapPaths/outdir = APRÈS, dans le top-down.**

**SÉMANTIQUE DES "LINKS" (corrigée par l'user):** `RKE2LAB_POLICY_LINK_<domain>_ENABLED` = faut-il
SYMLINKER ce domaine de manifests dans RKE2 — lu par `rke2lab-manifests-install.sh`
(`layer_is_policy_linkable` + `link_manifest_tree` fait des `ln -sfn`). "link" = le symlink, décision
PAR DOMAINE de manifests, pas un toggle logique abstrait.

See [[controlplane-to-osgi-migration-frame]] [[master-provisioning-state]]
[[cluster-seed-execution-state]] [[bootstrap-pipeline-contributable-vision]]
[[single-source-of-truth-before-logic]].
