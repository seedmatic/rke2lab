---
name: domain-registry-abstraction
description: Deferred future branch refactor/domain-registry-abstraction — unify the two domain-registry pairs once rule-of-three is met
metadata: 
  node_type: memory
  type: project
  originSessionId: 0ea4e055-08e0-405d-a509-7a32cda44c3e
---

DEFERRED future feature branch: **`refactor/domain-registry-abstraction`**.

Once both taxonomies exist — `ManifestDomainCatalog` (Stage B, deployment) and the new
`InfraDomainCatalog` (Stage A, provisioning, from [[config-restructuring-state]]) — the shape
*catalog → registrar → builder → registry, validate-on-build()* is duplicated. It rhymes and
is abstractable into a generic `DomainCatalog` / `DomainRegistry<D>` core.

**Do NOT do this during the config work.** Recorded as a deferred non-goal in the config
restructuring spec's Future Work (`docs/architecture/config/config-restructuring-spec.adoc` on branch
`feature/runbook-doctor`). Deferring is deliberate (rule of three):

1. The two registries differ exactly where abstraction is hard — manifest contribution is
   homogeneous (units) and validates a dependency DAG + acyclicity; infra contribution is
   heterogeneous (sealed `InfraConfigFragment` + centralized cast) and validates id↔type +
   presence. A base class from N=2 would have leaky seams precisely where they disagree.
2. The doctor is the third facet. The real abstraction is "a domain owns {config fragment,
   manifest units, doctor specialist}". The runbook-doctor branch is concurrently defining
   the specialist side — abstracting now captures 2 of 3 facets.

**Trigger to start the branch:** a third registry instance appears, OR the runbook-doctor
domain model (specialist ownership) lands — whichever first reveals the real variation axes.
Same discipline runbook-doctor states for its own checkpoint harness ("waits until checkpoint
#2 reveals the real variation axes").
