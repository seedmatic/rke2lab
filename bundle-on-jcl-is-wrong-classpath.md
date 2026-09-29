---
name: bundle-on-jcl-is-wrong-classpath
description: "INVARIANT (user, 2026-06-23): a bundle destined for the OSGi world (BCL) sitting on the flat host classpath (JCL) is a WRONG CLASSPATH — full stop, not 'wrong only if it splits a class or dupes a provider'. pax-logging was merely the case where the wrong classpath SHOWED (a 2nd org.slf4j provider broke the binder); felix.scr, the DS-API trio, manifests-core flat in tests were just as wrong with no visible symptom. Corollary: staged ⟺ off the JCL. MECHANISM (settled 4c): declarative `optional` scope on the boot-stack in the owning module (osgi/runtime) — Maven does not propagate optional deps, so it stays off every consumer's JCL by native rule, IDE-safe by construction; the staging extension re-resolves the stack by coordinate to stage it anyway. The afterProjectsRead model-mutation participant was a spike, now REJECTED in favour of the scope."
metadata:
  node_type: memory
  type: project
---

## The invariant (user reframing, 2026-06-23)

We came at this chasing a pax-logging bug: pax on a test classpath is a SECOND `org.slf4j` provider,
so slf4j binds nondeterministically and jGiven in-container resolution breaks. The fix looked like
"keep pax off the test JCL". But the user sharpened it into a general law:

> A bundle meant for the OSGi world (loaded by a Bundle ClassLoader) present on the flat host
> classpath (the JCL) is a **wrong classpath — period.** Not "wrong *if* it splits a class", not
> "wrong *if* it dupes a provider". Wrong by nature, even when no symptom appears.

pax was only the case where the wrongness was VISIBLE. felix.scr, the DS-API trio
(`org.osgi.service.component`/`util.promise`/`util.function`), `manifests-core` left flat on a test
classpath — all equally wrong, silently: they are bundles, they belong on a BCL inside Felix, their
presence on the JCL is a classpath defect that just hadn't bitten yet. This flips the work from
"fix a pax bug" to "restore a correct classpath", of which pax was merely the revealer.

## The corollary that drives the build: staged ⟺ absent from the JCL

The staging extension already COPIES bundles into `META-INF/bundles/` (and excludes them from the
exec-jar's flat shade). The missing half: it must ALSO remove them from the module's EFFECTIVE
classpath — the shade-exclude fixes the deployed exec-jar but NOT the test classpath, which is why
pax leaked into tests. One derivation (the `StagingClosure`) decides BOTH: what is staged is exactly
what is excluded from the JCL. Single source of truth — no hand-list, no per-module flag (a staged
bundle is JCL-excluded by NATURE, not by opt-in; see the rejected-flag note below).

## The mechanism — declarative `optional` scope (SETTLED 4c, 2026-06-23)

The boot-stack (pax + felix.scr/resolver + the DS-API trio) is declared `runtime` + `<optional>true`
in `osgi/runtime`, the one module that owns it. Maven does NOT propagate optional dependencies, so
the stack stays off every CONSUMER's classpath (the 3 exec, and any test module pulling runtime) by
a native resolution rule. This is the invariant made declarative: a bundle is off the JCL by its
scope, in ONE place, no per-module gesture.

Why this beats the alternatives we tried/considered:

- ✅ `<optional>true>` in the owning module — IDE-safe BY CONSTRUCTION (m2e runs the same Maven
  resolution; optional is dropped identically in CLI and IDE). One declaration. Deletes code.
- ❌ `AbstractMavenLifecycleParticipant.afterProjectsRead()` model mutation (the SPIKE) — it WORKED
  and was IDE-proven, but it is a programmatic mutation of every module's model where a scope rule
  suffices. REJECTED once `optional` was found: more machinery for the same effect. The participant
  (`JclExclusionParticipant`) was deleted.
- ❌ a per-bundle "jcl-excluded" flag — reintroduces a hand-list + the forget-risk that first let pax
  leak. "Off the JCL" follows from the scope, nothing to mark.

The CONSEQUENCE the scope creates, and how it is handled: optional removes the stack from the
resolved dependency GRAPH, so the build-time `StagingClosure` (which fans out over the graph) can no
longer SEE it to stage it. So the `StagingExecutionStrategy` re-resolves the boot stack BY COORDINATE
from the `BootStackJar` registry (groupId+artifactId, BOM-managed version) — independent of the
graph. The DS-API trio, previously staged by transitive LUCK (it rode the runtime-scope deps), is now
NAMED in `BootStackJar` as a PASSIVE layer, so it is staged deterministically, not by accident.

Two gestures remain, but now cleanly separated by concern (not by IDE-timing): the SCOPE keeps the
stack off the JCL (declarative, model-level), and the staging extension COPIES it into
`META-INF/bundles` (build output, by-coordinate). Single source = the `BootStackJar` registry + the
embed capability.

See [[osgi-system-export-resolution-only]] (the sibling invariant: what GOES to system-export carries
type-resolution only — this one is its mirror: what is a BUNDLE must not stay flat),
[[osgi-runtime-r4-boot-seam-state]], [[boot-decomposition-state]] (the chantier this closes),
[[system-space-world-universe-glossary]] (JCL/BCL).
