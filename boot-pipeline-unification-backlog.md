---
name: boot-pipeline-unification-backlog
description: "BACKLOG (raised 2026-06-22, osgi-boot-alignment chantier, NOT to do in it): unify the two embedded-Felix boot EXECUTORS — OsgiRuntime (prod, host-world) and FelixFrameworkExtension (test) — into a shared boot-pipeline, the executor counterpart of the boot-discovery model module. Deferred deliberately: it is design (abstracting two genuinely-different boot sequences), not the mechanical model extraction the current chantier does."
metadata:
  node_type: memory
  type: project
---

## The idea (user, 2026-06-22)

The osgi-boot-alignment chantier created `osgi/boot/boot-discovery` — the boot MODEL (the embed
capability, the `BootStackJar` registry, the `ClasspathBundleIndex`), shared by both boot executors
so prod and test agree on *what* to boot. The user asked: shouldn't `boot-discovery` be accompanied
by a `boot-pipeline`? — i.e. extract the boot EXECUTION too, so the *how* is shared, not just the
*what*. Under the new `osgi/boot/` aggregator, `boot-pipeline` would sit beside `boot-discovery`,
with `osgi/runtime` (the host-world executor) and the test `FelixFrameworkExtension` both consuming
it.

This is the STRONG form of "align the test boot topology onto prod" (one boot engine, two configs)
vs the WEAK form the chantier ships (two engines reading one model).

## Why it is BACKLOG, not part of osgi-boot-alignment

Decided with the user: add to backlog, do NOT do it in the current chantier. Reasons:

1. **Different concern.** Extracting the model (mechanical, safe) ≠ unifying two executors (design).
   Mixing them breaks single-topic / atomic-refactor discipline.
2. **The two boot sequences genuinely differ** — not a copy-paste to dedupe. Prod
   (`OsgiRuntime.boot()`) derives `system.packages.extra` by mirroring imports, pins start levels,
   streams embedded bundles into Felix's cache. Test (`FelixFrameworkExtension`) installs from the
   classpath, handles fragments, runs `resolve()` proofs, starts eager (no start levels). A shared
   `boot-pipeline` must ABSTRACT these differences — that is design work.
3. **Not needed** to unblock the chantier (the pax-closure split is solved by boot-discovery alone).

## How the trap revealed the boot family (the origin worth keeping)

The `osgi/boot/` family was not designed up front — it was forced by a trap. To single-source the
classpath primitives the test extension duplicated, the first move was to make `junit-testkit`
depend on `osgi/runtime` (compile scope) and call its `locateOnClasspath` / `exportsForImportsOf`.
That MIS-USED `runtime`: the testkit wanted only to *find* bundles, but `runtime` is the boot
EXECUTOR, so its whole closure came along — `pax-logging-api`, which *provides* `org.slf4j`. On
`doctor-core-test` (a jGiven in-container proof) that became a SECOND `org.slf4j` provider beside the
host logback, and the in-container bundles failed to resolve — a bare `resolve() == false` with no
reason (which in turn surfaced the need for `@FrameworkLog` / `felix.log.level` to even see why).

The fix was not `<exclusions>` (that hides the coupling — the user named it a smell). The lesson:
the testkit needed the MODEL (how to find bundles), never the EXECUTOR (how to boot them). Splitting
the model into `osgi/boot/boot-discovery` cut the closure at the root. And once the model was its own
module, the symmetric question fell out: the EXECUTION is duplicated too (OsgiRuntime vs
FelixFrameworkExtension) — so the same "regroup around boot" that produced boot-discovery suggests a
boot-pipeline beside it. The user crystallised it: *runtime is a bundle that defines the model, not
the runtime itself* — the boot concern wants its own family, model and execution side by side under
`osgi/boot/`.

## The duplication is now CONCRETE (staging-extension chantier, 2026-06-22)

The DS-API gate forced a new shared step that landed in BOTH executors by copy, exactly the drift
this backlog warns about — the user even called it at the time ("le maintainer en fait évoluer une
et pas l'autre"). The runtime closure that installs the DS-API trio as passive bundles now exists
twice: `OsgiRuntime.closeOverImports(stack, systemExported)` (prod) and the felix.scr import-fixpoint
inside `FelixFrameworkExtension.startScr()` (test) — same algorithm (close over MANDATORY imports a
stacked bundle needs, that no stacked bundle exports and the system bundle does not either, pulling
the index exporter in as a passive bundle), written twice. The SELECTION half is already shared
(`DiscoveryPolicy` in boot-discovery, consumed by both); the CLOSURE half is the remaining
duplication to fold. So the unification now has a concrete first target, not just the boot()/beforeAll
spine. See [[osgi-staging-extension-chantier]].

## Resume hint

Start from the two `boot()` / `beforeAll()` bodies; the closure (above) is the freshest duplication
to extract. Find the common spine (init framework → install at level → raise → await SCR → close over
imports) and the per-world variation (export derivation, fragments, start-level pinning). See
[[external-edges-chantier-handoff]] for the chantier that spawned this, and the `osgi/boot/`
aggregator it introduced.
