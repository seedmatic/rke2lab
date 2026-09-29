---
name: sweep-objectmapper-onto-codec-backlog
description: BACKLOG (world-gateway 2D, user 2026-07-01) — once the codec (gateway-document-codec / DocumentCodec) is fully in place across all 6 coordinates (after T9), sweep out every remaining hand-rolled `new ObjectMapper()` in Document producers/consumers so the codec is the SINGLE (de)serialization point. Per-coordinate migration removes them incrementally; a final sweep catches the stragglers.
metadata:
  type: project
---

## The goal (user, 2026-07-01)

As each coordinate migrates to its wire-record, its producers/consumers stop hand-rolling
`ObjectMapper`/`ObjectNode` and call `DocumentCodec.encode/decode` instead. The user wants a FINAL
sweep once all 6 coordinates are done (after T9): no Document producer/consumer should still hold a
private `new ObjectMapper()` — the codec is the one place that serializes a Document payload.

## Why not now

Each coordinate is migrated one at a time (T5-T9); a class that produces/consumes MULTIPLE
coordinates keeps its `ObjectMapper` until its LAST coordinate is migrated. E.g. as of T6:
- `RecordInterventionCommand` — MAPPER became dead once intervention-request (produce) + readiness-
  verdict (consume) both moved to the codec → removed in T6.
- `DefaultInterventionIntake` — still reads intervention-request via `mapper` until T6 consumer
  migration; `SystemdAdapterStage`/`Generalist` keep theirs until readiness-checkpoint/consultation
  (T8/T9).

## The sweep (do after T9, before/with T10)

Grep `new ObjectMapper()` under exec/ host/ osgi/ (excluding target/ and the codec module itself),
confirm each remaining one is NOT a Document producer/consumer (some may be legitimate non-gateway
JSON, e.g. pulumi outputs) — migrate the gateway ones onto the codec, leave the rest. Pairs with
[[world-gateway-2d-execution-state]] and the FIELD_* deletion at T10.
