---
name: validate-at-the-boundary
description: "Design rule (user, 2026-06-14): validation/normalization belongs at the BOUNDARY DELEGATE where foreign data or a foreign API enters — never as a defensive guard scattered in our own domain types. A requireNonNull on a required field of OUR record that no foreign path feeds is a defensive smell (it guards only our own callers); the record already enforces no-incomplete-state structurally. When a foreign API breaks our contract, add a delegate that 'bouche le trou' (translate its exception/shape into ours), an anti-corruption layer — don't leak its contract inward."
metadata:
  node_type: memory
  type: feedback
  originSessionId: 6fa6b30b-f578-4ee4-9ffa-806a1172c020
---

**THE RULE.** Validate ONCE, at the boundary delegate where foreign data / a foreign API enters the
system. The domain type then TRUSTS its callers. Do not sprinkle defensive guards (`requireNonNull`,
re-parsing, re-checking) inside domain types for data that only ever arrives through a boundary that
already validated it.

**Why:** it reconciles two of the user's CLAUDE.md rules that *look* like they conflict —
"no instances with incomplete state" (the type must reject a bad required field) and "always explicit,
never pass null" (caller discipline). They're the two ends of ONE invariant: a public record with all
required fields in its canonical constructor enforces no-incomplete-state STRUCTURALLY (you cannot
construct without supplying every field). A null-*content* check on top of that guards only OUR OWN
callers — which the "never pass null" discipline already covers — so it's defensive noise, not a
detector. The genuine boundary (deserialization, CLI parse, foreign SDK) is where validation earns
its keep.

**THE TWO CASES (they pull OPPOSITE ways — this is the discriminator).**
- **Foreign API breaks our contract** → the design hole is at OUR seam → add a boundary DELEGATE that
  "bouche le trou" (anti-corruption layer). Example: `Instant.parse` throws `DateTimeParseException`,
  NOT an `IllegalArgumentException`, so it ESCAPES a `main` that only catches IAE → a `parseWhen`
  delegate translates it into our uniform usage error. KEEP that — it's the rule in action.
- **Our own domain type, fed only through a boundary we control** → the boundary delegate already
  validates → a guard in the type is defensive. Example: `Intervention` (our record) is fed foreign
  data ONLY by `InterventionReader`, which rejects a missing required field to `Optional.empty()` at
  the boundary. So `requireNonNull` in the record's constructor guards only our own code → REMOVE it.
  (Removed ALL of them, incl. a pre-existing one, for uniformity — a half-migrated asymmetry is the
  worse smell. Caught 2026-06-14: "guard nulls, ça sent le mauvais design non?" + "quand c'est pas
  notre API … il faut mettre un délégué pour boucher le trou de design chez nous".)

**THE TELL.** A `requireNonNull` (or any validation) that can NEVER fire because every caller is our
own explicit code = defensive smell, the API isn't the problem. A `requireNonNull` (or a translating
delegate) AT the point foreign data/exceptions enter = correct, it's the boundary doing its job.
Distinguish by asking: "does any FOREIGN path reach this constructor/method without passing a
delegate I control?" No → trust the caller. Yes → that path needs the delegate, put the check THERE.

**NOT in tension with the layered error contract** ([[hub:error-handling-layered-contract]]): that says a
present-but-broken thing must THROW, not be masked (e.g. the same review's CRITICAL — a corrupt ledger
must propagate, not fold to empty). Both are "errors surface at their true origin, never deported":
the boundary delegate is exactly where a foreign break has its origin. Surfaced during the
[[intervention-provenance-state]] final review; validates [[hub:works-best-from-concrete-code]] (the rule
crystallized from a concrete reviewer suggestion the user rejected). See also
[[hub:user-profile-senior-dev]] — errors-as-logs is the deepest pain, and a guard-that-never-fires plus a
swallowed-corruption are two faces of deporting the decision away from its origin.
