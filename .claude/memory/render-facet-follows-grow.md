---
name: render-facet-follows-grow
description: "The in-cluster render facet (which layers publish + delivery) follows the last grow, recorded as a local-config manifest.yaml at the branch root and read back by the render — fixes the mesh=false divergence where the in-cluster publish hardcoded a different facet than the grow's Pulumi stack. IMPLEMENTED (compiles + full-branch build green), NOT yet grow-validated."
metadata: 
  node_type: memory
  type: project
  originSessionId: f37aea8f-e85f-4a18-9278-ff26bcb03565
  modified: 2026-09-05T16:23:53.937Z
---

**Chantier (2026-09-05, `feature/nixos-node-substrate`, docs-first then implemented) — the render facet is the operator's authoring concern with ONE source (the Pulumi stack `rke2lab:manifests`), read at the grow; the in-cluster render must render EXACTLY what the last grow decided.**

## The bug it fixes
The in-cluster `publish` (Main.java `manifestsFacet()`) HARDCODED the facet with `mesh=false`, while the grow's stack has `mesh: "true"` (Pulumi.dev.yaml `rke2lab:manifests.publish.mesh`). So the in-cluster render synthesised a DIFFERENT layer set than the grow — a mesh-layer change (e.g. the funnel-state Job) never reached `manifests/<cluster>`, and a source push touching only mesh produced no diff → no push (why the render `kx4xf` succeeded but the branch stayed at `e2896653f`). Confirmed in source: `ManifestSynthesisScenario:495` `.mesh(publish.mesh())` drives the synth-time domain filter; `Main.java:201` defaulted mesh=false.

## Design (converged with user, Option 3 / (b) refined) — the branch RECORDS its render facet
- **SSOT = the Pulumi stack**, read at the grow (FacetContributor → Amendment.FACET → synthesis). The synthesis is source-agnostic (consumes only the JSON facet).
- **The branch is self-describing**: every render writes a k8s ConfigMap `manifest.yaml` at the ROOT of `manifests/<cluster>`, annotated `config.kubernetes.io/local-config: "true"` (nothing applies it; it also sits outside every Flux Kustomization path), carrying the facet. `git show manifests/<cluster>:manifest.yaml` shows how the branch was rendered.
- **Written by the FLOW, not a ManifestsUnit**: at synthesis time only the RESOLVED `ManifestDomainPolicy` survives (raw facet + delivery.push are NOT retained), and the exploder nests every resource under `<layer>/<domain>/<package>/` with no root path. So `ManifestSynthesisScenario` (which holds the raw `ManifestsRunbookInput.facets` + the materialisation root) writes it directly at the worktree root (where the exploder already lands `.gitattributes`), post-synthesis, before the delivery `stageAll()`.
- **Read by the render wrapper, decoupled from the worktree**: the render worktree is EMPTIED by the delivery (`GitCli:67` `rm -rf .`, keeps HEAD) and the in-cluster source clone is a shallow single-ref fetch (no `manifests/<cluster>` local), so the read is a targeted `git fetch origin manifests/<cluster>` + `git show FETCH_HEAD:manifest.yaml` (`git archive --remote` is DISABLED on GitHub) in the flake wrapper (shared by in-cluster PaC + operator standalone publish), auth via ephemeral `http.extraheader` (no persisted credential), `yq '.data["facet.json"]'` → `-Drke2lab.manifests.facet.file`.
- **Fixpoint**: grow writes manifest.yaml (from Pulumi) → in-cluster reads it → renders (reproducing the same manifest) → pushes. Only the grow CHANGES it. Provenance (rev/time) rides the COMMIT, not the manifest → no empty-commit churn. **Drift = hand-edit the branch manifest.yaml** (git-native); the next grow resets it. No Pipeline param.
- **The in-cluster render orphans + force-pushes the branch fresh each render** (branchExists checks local refs only; the source clone lacks the branch) → the remote branch = exactly the last render (no accretion). Fine for determinism; the read is a deliberate cross-branch fetch.

## Files (IMPLEMENTED, all compile -Pclaude + user's full-branch build no-tests SUCCESS; NOT yet grow-validated)
- `osgi/.../manifests-bdd/.../ManifestSynthesisScenario.java` — `recordRenderFacet(root, facets)` on the When stage: serialises `facet.facets()` (Jackson) into a ConfigMap `manifest.yaml`, `config.kubernetes.io/local-config`, `data.facet.json='<json>'`; called `rendered.ifPresent(w -> recordRenderFacet(w.path(), facet.facets()))` after `synthesize`.
- `exec/manifests-cli/.../Main.java` — `runForPublish()` prefers `recordedFacet()` (reads `-Drke2lab.manifests.facet.file`, raw JSON) else the operator posture + arm `delivery.push`; the recorded facet carries its own delivery, used verbatim.
- `flake.nix` — render wrapper: `+pkgs.yq-go`, `GIT_SSL_CAINFO=${pkgs.cacert}/...`, best-effort `git -c http.extraheader fetch --depth 1 origin manifests/$cluster` + `git show FETCH_HEAD:manifest.yaml | yq '.data["facet.json"]'` → tmp → conditional `-Drke2lab.manifests.facet.file`. (nix build + shellcheck green.)
- **+ logs fix A** (same lot): `RenderPipelineManifestsUnit` render-publish step `trap 'cat .local.d/manifests-publish.log' EXIT` — the publish's pax-logging drains to a FILE (LogFileSeed), not stdout, so the container showed only Felix boot warnings; the trap lifts the narration into the container logs.
- Docs: `docs/architecture/cluster-api/pac-in-cluster-render-spec.adoc` §`[[render-config]]` (+2 troubleshooting rows) + `docs/architecture/atlas/manifests.adoc` Diagram O.

## NEXT
Commit/push (uncommitted) → the funnel fix (`4ffeb38c6`, already pushed) + this. Then a **re-grow** validates end-to-end: the grow writes `manifest.yaml` (mesh=true) → an in-cluster render reads it → renders mesh → the funnel backup-Job fix (see [[cold-start-cleanup-and-funnel-cert-persistence]]) finally flows in-cluster too, and the container logs show the publish narration. See [[manifests-publish-in-cluster-render]] [[cold-start-cleanup-and-funnel-cert-persistence]].
