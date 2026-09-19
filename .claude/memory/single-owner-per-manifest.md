---
name: single-owner-per-manifest
description: RÈGLE — un objet K8s a exactement UN owner réconciliateur (Flux XOR operator/controller qui le mute) ; deux réconciliateurs continus sur le même objet = flap
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 57ab666d-7ffd-4083-b5a4-7b41b85cdd6a
  modified: 2026-09-12T16:47:20.016Z
---

**RÈGLE (★ user, 2026-09-12) : un manifest K8s ne peut avoir qu'UN SEUL owner** — Flux, le flox-controller, le rke2-adoption-controller, un operator, … — jamais deux réconciliateurs *continus* sur le même objet.

**Why :** deux owners qui force-apply (SSA) le même champ se battent → flap. Mis en évidence par l'egress tailscale : le tailscale-operator MUTE `spec.externalName` du Service egress en boucle ; si Flux rend ce Service, Flux (force-apply) le remet à sa valeur git → flap → egress cassé. Donc l'objet muté par un operator NE PEUT PAS être aussi Flux-owned.

**How to apply :**
- Attribuer chaque objet à un owner unique. Un objet qu'un operator mute (egress Service, etc.) sort du set Flux-rendu → possédé par le controller qui le mute (ici le [[cp-endpoint-reach-tailnet-headscale-migration]] rke2-adoption-controller crée/réconcilie l'egress idempotemment).
- **Nuance (pas une violation)** : un webhook d'ADMISSION qui mute un objet ENFANT ≠ un réconciliateur qui se bat sur le même objet. Le flox-controller mute les **Pods** (créés par le ReplicaSet), PAS le Deployment Flux-owned → owner unique par objet préservé. La livraison FloxEnv d'un workload reste donc GitOps-compatible.
- Signal d'alerte en revue : « qui d'autre écrit ce champ/objet ? » Si un operator le touche, ne le rends pas via Flux.

Owners connus dans rke2lab : Flux (manifests rendus depuis git), tailscale-operator (Connectors + wiring egress), flox-controller (mutation Pods via webhook), rke2-adoption-controller (egress + patch kubeconfig). See [[all-workloads-on-flox-runtime]] [[flox-env-migration-design]].
