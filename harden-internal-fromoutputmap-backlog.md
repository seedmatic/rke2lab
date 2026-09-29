---
name: harden-internal-fromoutputmap-backlog
description: BACKLOG (user 2026-07-01, right AFTER world-gateway 2D) — widen the fromOutputMap(Object)→typed hardening to the doctor-records-INTERNAL record↔map converters that are NOT gateway wire (ExpectationPredicate.fromOutputMap, ResolutionPredicate, and any other Object-accepting fromOutputMap that never crosses the seam). Same discipline as the wire migration (no open Object door), but internal serialization, so its own step.
metadata:
  type: project
---

## The goal (user, 2026-07-01)

The `fromOutputMap(Object)`→`fromWire(TypedRecord)` hardening in 2D (praised: "on laisse pas passer
n'importe quoi") applies to GATEWAY WIRE converters. The user wants the SAME hardening extended to the
INTERNAL record↔map converters in doctor-records — but as a SEPARATE step right after 2D.

## In scope (internal, NOT gateway wire)

Converters that (de)serialize a record to/from a `Map`/`Object` but NEVER cross the world-gateway
seam — pure OSGi-internal domain serialization:
- `ExpectationPredicate.fromOutputMap(Object)` + its `kind`-discriminator dispatch to
  `ResolutionPredicate.fromOutputMap(Map)`.
- any other `fromOutputMap(Object)` in doctor-records / doctor-core that a T5-T9 wire migration did
  NOT already convert. Grep `fromOutputMap` under osgi/domains/doctor, minus the ones now on wire.

## Out of scope (already done or done in 2D)

- Gateway-wire converters: InterventionReader (T7, done), ConsultationReportReader/ExpectationReader
  (T9, consultation coordinate). Those become `fromWire(TypedRecord)`.

## Why separate

2D's contract is the host↔OSGi WIRE (the 6 coordinates). Internal record↔map is a different concern
(domain persistence shape), so hardening it is its own pass — keeps 2D's diff about the seam. Do it
immediately after the 2D arc (T10 flip) lands. See [[world-gateway-2d-execution-state]]
[[sweep-objectmapper-onto-codec-backlog]].
