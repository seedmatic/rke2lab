---
name: jspecify-nullmarked-default-backlog
description: BACKLOG (user 2026-07-01, during 2D T6) — adopt JSpecify @NullMarked at package level to make the whole codebase non-null BY DEFAULT (only explicit @Nullable is an exception), instead of scattering @Nonnull. One annotation per package-info.java (where @Version/@GovernedBy already live). Zero runtime. Post-2D chantier.
metadata:
  type: project
---

## The goal (user, 2026-07-01)

The user's codebase rule: a contract must EXPLICITLY declare which values may be empty — no null
values (too fragile). Rather than annotate `@Nonnull` everywhere, invert the default: non-null unless
marked `@Nullable`.

## The answer — JSpecify `@NullMarked`

[JSpecify](https://jspecify.dev) `org.jspecify.annotations.@NullMarked` on a `package-info.java` (or
module) makes ALL types in that scope non-null by default; only explicit `@Nullable` is an exception.
Chosen over the old JSR-305 `@ParametersAreNonnullByDefault` because:
- ONE annotation per package (not per method/param), and it covers params, returns, fields AND
  generic type-arguments (`List<@Nullable String>`) — JSR-305 does not do generics.
- Emerging standard (Google, JetBrains, Oracle); understood natively by IDEs + checkers (NullAway,
  Checker Framework).
- `CLASS`-retention, zero runtime dependency — no risk of leaking into the host/OSGi realms.

## Fit with this repo

Every package already has a `package-info.java` carrying `@Version` + sometimes `@GovernedBy` — so
`@NullMarked` is a one-line add at the same place. Could later be a gate-checked convention (every
exported package is @NullMarked). Wire records (world-gateway) benefit directly: an optional field is
`Optional<T>` (the [[sweep-objectmapper-onto-codec-backlog]]/2D discipline), everything else non-null.

## Scope

Its OWN chantier (add JSpecify to bom/, @NullMarked every package-info, optionally wire NullAway in
the build) — NOT to be slipped into 2D. Do after the 2D arc. See [[world-gateway-2d-execution-state]].
