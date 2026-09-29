---
name: osgi-logs-flow-to-host
description: "DECISION (user, 2026-06-19/20, R4): logging follows the bootstrap direction — the host bootstraps the embedded OSGi framework, so OSGi logs (framework + SCR + bundle) flow OUT to the host's logback, NOT the host reaching INTO the OSGi world. ★ SHIPPED via Pax Logging with StaticLogbackContext=true (commit 93504f89), NOT the Direction-A felix.log/LogListener relay first proposed. The key insight that flipped it: with StaticLogbackContext pax REUSES the host's logback LoggerContext, so the path (slf4j→LogService) and the destination (the host backend) are DECOUPLED — logs route through the OSGi LogService yet land in the host's context. One shared context beats a per-entry relay, and pax-logging-api becomes the sole org.slf4j provider inside the framework (the system bundle stops exporting it → no R1 split)."
metadata:
  node_type: memory
  type: project
---

## The principle (user, 2026-06-19)

> "Le host bootstrappe le moteur OSGi, donc c'est logique que les logs atterrissent dans les logs du
> host et pas dans OSGi."

The logging bridge follows the **direction of the bootstrap**. `OsgiRuntime` is host-world infrastructure
that OWNS the embedded Felix framework — so it also owns draining that framework's logs OUTWARD to the
host's slf4j/logback. The host never reaches INTO the OSGi world to emit or capture logs from inside it.

## ★ How it actually shipped — Pax Logging, NOT Direction A (the design loop that flipped it)

This is a worked example of the expose→react loop ([[hub:works-best-from-concrete-code]]). FIRST pass:
Pax was REJECTED — the reasoning was "pax-logging-api routes slf4j calls to the OSGi LogService, so logs
stay INSIDE the OSGi world, the opposite of the user's direction" — and Direction A (install
`org.apache.felix.log` + a `LogListener` relaying each `LogEntry` to slf4j) was chosen. **That reasoning
was wrong, and the user proved it.** The flaw: it conflated the *path* with the *destination*. The
deciding fact is the config `org.ops4j.pax.logging.StaticLogbackContext=true` — with it,
pax-logging-logback does NOT build its own isolated backend; it **reuses the HOST's logback
`LoggerContext`**. So calls travel through the LogService (pax mechanics) yet are written by the host's
backend — exactly the user's direction. Path (slf4j→LogService) ≠ destination (host logback); the flag
decouples them.

Why the shipped design is BETTER than Direction A:

- ONE shared logback context (host-owned), not a per-`LogEntry` relay bridge that copies events out.
- pax-logging-api is the SOLE `org.slf4j` provider inside the framework → `OsgiRuntime` drops `org.slf4j`
  from the derived system-exports (so the system bundle isn't a second binder → no R1 split scar).
- framework/SCR/bundle events drain at WARN into the LogService
  (`org.ops4j.pax.logging.service.frameworkEventsLogLevel=WARN`), so SCR activation failures (which
  Direction A was invented to surface) are visible — the original motivation is still met.

Implementation: `OsgiRuntime` installs pax-logging-api + pax-logging-logback at `START_LEVEL_LOGGING`
(before felix.scr and the model bundles, so the LogService is live before anything logs); embedded form
`embeddedPaxLogging(apiName, logbackName)`; the host (seed-master) supplies logback-classic as the
`LoggerContext` pax reuses. Proven in `HostSeamEmbeddedFelixTest` and the `pulumi preview` run.

The LESSON (worth more than the fix): a confidently-argued REJECT can be wrong on a single decoupling
fact; the user's concrete proof (the config flag) beat the abstract argument. On this codebase, validate
against the runtime/config reality, not the plausible-sounding model — the rke2lab form of
[[build-verification-gotchas]] / "runtime is the judge".

## Why this surfaced

R4 milestone A: a `@Component` (FloxRuntimeAssetService) failed to activate and SCR **swallowed** the
exception — the host saw only a silent `null` from `awaitService`. felix.scr logs activation failures via
the OSGi **LogService**, which nobody was draining → invisible. Flying blind on the critical increment is
unacceptable; the boot component had to be observable for the `pulumi preview` go/no-go. That need is what
drove the logging bridge — first proposed as Direction A (felix.log + LogListener relay), then superseded
by Pax + StaticLogbackContext above once the user proved Pax meets the direction.

Note on application logs: manifests-core's `@Component`s log via `org.slf4j.Logger`. Under Pax,
pax-logging-api is the `org.slf4j` provider inside the framework, so those calls route through the
LogService into the host's reused logback context. (Pre-Pax this would have bound to a flat
system-exported `org.slf4j`; now the system bundle no longer exports it — pax is the sole provider.)

## Superseded approach (kept only to explain the note's history)

- **Direction A — felix.log + LogListener relay** (first chosen, then dropped): install
  `org.apache.felix.log` and attach a `LogListener` to the `LogReaderService` relaying each `LogEntry` to
  slf4j. Workable, but a per-entry copy-out bridge rather than one shared context, and it left
  application slf4j logs on a separately system-exported `org.slf4j`. Replaced by Pax +
  `StaticLogbackContext` (above), which is one host-owned context and a single slf4j provider. If Pax is
  ever revisited, reconcile with the top of this note first.

See [[osgi-runtime-r4-boot-seam-state]] (THE SPEC) [[osgi-logging-and-cli-debt]] (the now-superseded Pax
plan) [[osgi-system-export-resolution-only]] (org.slf4j is one of the flat system-exports)
[[seedlog-logback-migration-backlog]].
