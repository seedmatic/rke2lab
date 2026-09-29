---
name: bdd-null-hygiene-frontier-rule
description: "User rule pushed hard (2026-07-07): ZERO null ambiguity in OUR model. A null is decorated at the FRONTIER (external nullable API) — requireNonNull if required, Optional if truly optional — never reasoned on inside our code. No != null, no @Nullable, no null sentinel in records/fields/scenario-state."
metadata:
  type: feedback
---

**The rule (user, 2026-07-07, repeated several times):** "zero ambiguite dans notre code a propos des valeurs nulles" / "quand c'est chez nous et pas a la frontiere, on n'accepte pas de reposer sur des valeurs nulles."

**Why:** a `!= null` inside our logic is either dead defensiveness against our own model (a field we always set) or a silent-skip that hides a broken contract. Both are ambiguity. The user wants the nullability question ANSWERED once, at the boundary, and never re-asked downstream.

**How to apply — the frontier vs chez-nous split:**
- **At the frontier** (an intrinsically-nullable EXTERNAL API: JUnit `Store.get`, OSGi `awaitService`, jGiven's `ReportModel` store slot, JDK `Map.get`, a record's compact-ctor arg-guard) → decorate IMMEDIATELY:
  - REQUIRED (the seeder/driver always provides it) → `Objects.requireNonNull(x, "msg")`. This is NOT self-protection; it converts the nullable return into a guaranteed model value AND fails loud + local if the contract breaks. Keep it even when "by design it can't be null" — the store API's return type is nullable, so the conversion must happen somewhere; do it at the door with a clear message.
  - TRULY OPTIONAL (a test seeds it, live resolves elsewhere) → `Optional.ofNullable(...)`, default `Optional.empty()`.
- **Chez nous** (our records, fields, scenario-state) → ZERO null, ZERO `!= null`. Optionality is carried by `Optional`, never a null sentinel. Read via `.orElseGet(...)`/`.ifPresent(...)`, not `x != null ? x : ...`.

**Concrete fixes shipped (ClusterSeed bdd/, 2026-07-07):**
- probe overrides `injectedProbe`/`clusterProbe`: `@MonotonicNonNull X` + `x != null ? x : liveProbe()` → `Optional<X> = empty()` + `.orElseGet(this::liveProbe)`.
- outputs sink + run-model holder: `Optional<AtomicReference<...>>`, `.ifPresent(...)`.
- dropped `hostFacts.readinessLogger() != null` guards (readinessLogger is a HostFacts record component, ALWAYS set — dead defensiveness).
- doctor `awaitService(ConsultingService)` : `doctor == null || ...` reasoning → `Optional.ofNullable(connection.awaitService(...)).ifPresent(doctor -> ...)` (decorate the OSGi frontier, don't reason on raw null).
- `HOST_FACTS`/`CONNECTION` store reads → `requireNonNull` (required context; the `facts != null &&` silent-skip removed).

**LEGITIMATE remaining `!= null`** (all frontier, keep): JDK `Map.get` result, `@MonotonicNonNull` jGiven DAG-injected stage fields read through `requireNonNull` guards (the set-once-by-reflection convention), store optional-channel probes.

**Verify, don't trust:** grep the package for `!= null|== null|@Nullable|orElse(null)` after any bdd change; every hit must be a frontier, not our model. See [[bdd-context-injection-carrier]] [[collaborative-design-method]].
