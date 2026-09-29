---
name: no-system-out-use-logger
description: "feedback — never use System.out/err, not even in throwaway debug probes; use the project logger (OSGi LogService / slf4j) at the right level."
metadata:
  type: feedback
---

**Never write `System.out`/`System.err` — not even in temporary, throwaway diagnostics injected
mid-debug.** Use the project logger (in OSGi bundles: the OSGi `LogService`; via the slf4j façade
already on the classpath). A diagnostic probe is often worth KEEPING rather than deleting — promote
it to `trace`/`debug` level instead of removing it, so the wiring it inspects stays observable.

**Why:** `System.out` bypasses the logging backend (no level control, no routing, no correlation),
pollutes test/CLI output, and on the OSGi runtime path it sidesteps the very `LogService` the
migration is standardising on ([[osgi-logging-and-cli-debt]]: slf4j façade → Pax Logging →
LogService). Stated by the user 2026-06-19 during R3, twice — it is a hard rule, not a preference.

**How to apply:** when about to print state to understand a failure, add a logger line at `trace`
(or `debug`) on the class under inspection, not `System.out`. Keep it if it documents a non-obvious
wiring fact; drop it only if truly noise. Tests assert via the framework's messages (JUnit
assertion messages), which need no printing — a passing test is silent.

See [[osgi-logging-and-cli-debt]] [[osgi-runtime-r3-consume-references-state]].
