---
name: gateway-crossing-three-natures
description: "Why the ports/gateway structured-but-opaque-transfer rule is rich yet hard to APPLY (user, 2026-07-08 — 'on a bloqué dessus plusieurs fois, surtout moi'): the same object has THREE natures along one crossing (live typed → opaque JSON String → live typed, maybe on another classloader), and NOTHING at the point of use signals which phase you're in. The understanding is there; what's missing is a LOCAL, observable signal of the crossing phase."
metadata:
  type: feedback
---

**The felt difficulty (user, 2026-07-08):** the ports + world-gateway rule — transfer STRUCTURED data
that is OPAQUE to the app carrying it — is a rich rule the user has repeatedly blocked on ("surtout
moi"). Worth naming WHY, because the block is not a discipline failure; the rule lacks an observable
form at the point of use.

**Why it is hard — the same object has THREE natures along ONE crossing, and they pull apart:**

1. *Typed at both ends* — each world manipulates a real record (`ReadinessCheckpoint`,
   `ObservationWire`) with fields.
2. *Opaque in the middle* — the transport (`Document`) must know NOTHING of its cargo: `domain`,
   `coordinate`, and a `payload` that is just a String ([[document-seam-cannot-expose-jackson-jsonnode]]
   — even a `JsonNode` payload once leaked the jackson bundle across the flat seam → `LinkageError`).
3. *One copy of the frontier* — frontier types (`type=seam`) are shared FLAT across realms; domain
   types (bundle-loaded) NEVER cross ([[osgi-system-export-resolution-only]]).

So a `ReadinessCheckpoint` is a live type when the scenario builds it, a JSON String inside
`Document.payload`, then a live type again at arrival — possibly loaded by a DIFFERENT classloader.
**Nothing in the code, where you write, tells you which of the three phases you are in** — nor whether
a boundary you are crossing is a seam (shared) or a domain edge (bundle, must serialize). You
reconstruct that mentally at every site. THAT is the block: the rule is correct; a LOCAL signal of the
crossing phase is missing. Same shape as the seam DX pain ([[system-exports-seam-gate-backlog]] §test
twin): the truth exists (the codec, the seam derivation) but has no observable form at the usage point.

**Encouraging, and worth remembering: the user's hot intuition is already right.** Two correct reflexes
this session WITHOUT deriving them: "on doit traverser en JSON sinon on va trop transpirer" (felt phase
2, opacity, before it was formalized — a mixed live-object-plus-JSON return across the front-door would
have forced a composite carrier that either can't be cast host-side or grows the frontier); and "on a
déjà un ObjectMapper" (felt the frontier codec must be ONE, not re-instantiated — already tracked as
[[sweep-objectmapper-onto-codec-backlog]]). The understanding is present; the tooling to make it
observable is not.

**Not to formalize now (user's explicit choice) — but the framing to carry into the future chantier:**
whatever we build to make this observable should give a LOCAL signal of "which crossing phase / which
kind of boundary" at the write site, the way an executable derivation (not a doc) made the seam rule
tractable. Sits next to [[world-gateway-frontier-discipline]] (WHICH words may cross) as its dual: this
one is about how HARD the crossing is to SEE, not which vocabulary is allowed. See
[[world-gateway-document-design]] [[document-codec-instance-in-2d-backlog]].
