---
name: osgi-runtime-r4-resume-state
description: "R4 SHIPPED 2026-06-20 (feature/osgi-runtime-r4-boot-seam) — Felix boots inside seed-master's Pulumi.run callback, the host seam consumes the registry, full pipeline runs under embedded Felix (pulumi preview, 0 errored). This note WAS the blow-by-blow resume point; it has been collapsed to a thin pointer per the prune-the-how discipline ([[merge-from-target-worktree]] §4). The durable what/why lives in the design-rule notes linked below; the step-by-step how lives in git history (19 commits, 25a16b75..the merge)."
metadata:
  node_type: memory
  type: project
---

## R4 shipped — where the knowledge lives now

The architectural lift (one world → two worlds branched, host boots Felix, reads the registry) is done
and proven by `pulumi preview` (0 errored, full pipeline under the embedded Felix). The pas-à-pas (work
items A→D, the cert domino chase, pre-reload resume points, ★ status markers) was process narrative — it
is in git history, not here. The DURABLE knowledge it produced is split across focused notes:

- [[osgi-runtime-r4-boot-seam-state]] — the spec/carto + the shipped summary (embed 5 bundles intact,
  start-levels, derived system.packages.extra, single awaitService seam).
- [[osgi-system-export-resolution-only]] — the invariant (system-export = type resolution only;
  designed-for-OSGi criterion; the as-shipped 5-bundle embed set).
- [[osgi-logs-flow-to-host]] — logs follow the bootstrap direction; Pax + StaticLogbackContext.
- [[r4-resolver-service-ification]] — the Resolver is a service, not a flat lib.
- [[synth-context-channel-rule]] — ThreadLocal-vs-@Reference by the ownership invariant.
- [[capn-cert-ownership-incoherence]] — the cross-world cert incoherence, lifted whole (IncusIdentityMaterial).
- [[migration-branch-no-fallback]], [[null-arg-is-a-rule-violation]], [[prefer-non-static-inner-keep-the-graph]],
  [[dual-path-inline-until-r5]] — the design rules/steers that surfaced under R4.

## Backlog dominoes (live — homed in their own notes)

1. ~~The 2 CLIs still ServiceLoader + shade flat~~ — SHIPPED 2026-06-20: both CLIs boot embedded Felix
   and read the registry; the boot-seam model generalises → [[cli-osgi-migration-carto]].
2. Host-world null-arg violations (pre-existing) → [[null-arg-is-a-rule-violation]] (the 4 named sites).
3. ThreadLocal `current()` readers not migrated — fine where they are (all under bind), no action owed
   unless one moves out of synthesis scope → [[synth-context-channel-rule]].
4. ~~manifests-core not yet demoted compile→runtime~~ — DONE 2026-06-20: demoted in all three exec poms
   (CLIs + seed-master), machine-enforcing path 2; the realgraph fixture that blocked it is gone.
5. Worktree-provisioning automation (3 gaps hit running preview) → [[worktree-provisioning-handoff]].

## Validation discipline (unchanged, durable)

`flox activate -- ./mvnw clean package -Posgi -Dmaven.build.cache.skipCache=true -DskipTests=false`.
NEVER `mvn install` (enforcer; `-am` from sources). NEVER `-Plive up`. Count surefire reports (green w/o
"Tests run:" = skipped). Verify builds myself before committing.

See [[osgi-runtime-r4-boot-seam-state]] [[memory-synthesis-prune-the-how]] [[merge-from-target-worktree]].
