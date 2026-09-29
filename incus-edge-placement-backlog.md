---
name: incus-edge-placement-backlog
description: Backlog (user, 2026-06-30) — the incus manifest synthesis + the incus edge placement: should synthesis become an OSGi gateway service (OSGi-side only) and where does the incus edge actually live? Surfaced by the cdk8s dual-realm finding. Defer until after isolation→2D→capstone.
metadata:
  type: project
---

Surfaced 2026-06-30 while confirming cdk8s is a legit host-side dual-realm library (the host builds
`org.cdk8s.App` in `IncusResourceBootstrap` to synthesize incus host-slot manifests). The user:
"les manifests incus mériteraient d'avoir un service dans la gateway exposé, pour jouer la
synthétisation des manifests uniquement côté OSGi. mais j'imagine que c'est plus large que cela, où
se trouve l'edge incus ? c'est lui qui doit diriger la place qu'occupe l'edge incus dans le système.
je pensais qu'on l'avait placé déjà dans le monde OSGi. … on parle de ça plus tard."

**The question (two layers):**
1. Incus manifest synthesis (today host-flat via cdk8s in `IncusResourceBootstrap`) could become an
   OSGi gateway-exposed SERVICE — synthesis happening OSGi-side only, the host consuming the result.
   That would remove the host's cdk8s usage → cdk8s would become staged-ONLY (a plain OSGi bundle),
   no longer a dual-realm flat∧staged case at all.
2. Broader: WHERE is the incus edge? The user expected it already in the OSGi world. The incus edge
   should govern the place the incus-edge occupies in the system. This ties to the external-edges
   chantier ([[external-edges-chantier-handoff]]) — pulumi-edge is the template, ssh-to-age-edge
   shipped; incus/cluster/host-fs are the remaining boundaries.

**Why deferred:** larger than the realm-library-isolation increment (it would move synthesis across
the seam, an architecture change, not a build-disposition fix). The isolation increment correctly
treats cdk8s as a legit dual-realm lib FOR NOW (verified host usage). This backlog would later make
that moot by relocating the synthesis. Sequence: AFTER isolation → 2D → remote capstone.
See [[realm-library-isolation-state]] [[external-edges-chantier-handoff]] [[pipeline-orchestration-osgi-vision]].
