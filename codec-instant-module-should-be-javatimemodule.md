---
name: codec-instant-module-should-be-javatimemodule
description: The codec's hand-rolled InstantModule should become jackson's official JavaTimeModule (jackson-datatype-jsr310) — the T6 "avoid jsr310 ServiceLoader" reasoning was flawed; explicit registration is safe.
metadata:
  type: project
---

The `gateway-document-codec` registers a **home-made `InstantModule`** (2-line SimpleModule:
`Instant.toString()` / `Instant.parse`). The user challenged this (2026-07-01): jackson has a
**dedicated feature for date-time types**.

Facts verified:
- Jackson version in use = **2.22.0** (`com.fasterxml.jackson.*`), NOT Jackson 3.x (`tools.jackson.*`
  where `DateTimeFeature` moved java.time INTO databind core). The user's linked `DateTimeFeature` /
  `tools.jackson.databind.ext.javatime` classes are **Jackson 3.x** — not our namespace.
- On 2.x, jackson-databind core has **ZERO** java.time support (all "Instant" hits in the jar are
  `*Instantiator` false-positives). `jackson-datatype-jdk8` (already used, for Optional) also does
  NOT touch Instant — jdk8 = Optional/Stream only.
- The dedicated feature on 2.x = **`jackson-datatype-jsr310`** → **`JavaTimeModule`**. Complete,
  official, well-tested handler for Instant + all java.time.

THE REVISED DECISION: the T6 rationale for the home-made module ("avoid jsr310's `ServiceLoader<Module>`
discovery surface, the realm-isolation regression") was **flawed**. That surface only bites when
something calls `findAndRegisterModules()`. The codec registers modules **EXPLICITLY**
(`registerModule`), exactly as it already does for `Jdk8Module`. So `JavaTimeModule` is just as safe to
register explicitly as jdk8 already is — no ServiceLoader exposure. Reinventing a slice of it is the
NIH anti-pattern.

DONE (2026-07-01, commit `9af2c777`): swapped `InstantModule` → jackson's `JavaTimeModule`
(`jackson-datatype-jsr310`), registered EXPLICITLY with `WRITE_DATES_AS_TIMESTAMPS` disabled (keeps
the exact ISO-8601 wire form). Declared jsr310 in the codec pom; the `type=library` `seedRealmLibraries`
picks it up automatically (third-party bundle exporting a `domainImports` package not in boot-stack) —
NO extra staging code. Verified DUAL in the uber-jar: `META-INF/bundles/jackson-datatype-jsr310.jar`
(staged) + 64 flat `com/fasterxml/jackson/datatype/jsr310/*` classes (host). `InstantModule.java`
deleted. Reactor + all tests green, SCHEMA_CONCORD 0/0. THIS BACKLOG IS CLOSED.
