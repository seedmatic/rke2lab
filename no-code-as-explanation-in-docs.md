---
name: no-code-as-explanation-in-docs
description: "Feedback — architecture docs explain with C4/UML figures + prose, NOT Java/shell code walls; the reader can already code"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 5f9d28b3-36b6-40f7-af59-2640e16975ad
  modified: 2026-08-23T07:45:47.317Z
---

In architecture docs (`docs/**/*.adoc`), do NOT paste Java/shell code blocks "as
explanation." The user's words: *"le java on sait coder, on a pas besoin comme
explication"* and *"moi je préfère des figures C4/UML"*. The implementation lives
in the repo; a doc that reproduces it as a code wall adds noise, not understanding,
and rots the moment the code changes.

**Why:** the reader is the developer — they can read the code. A doc earns its place
by carrying what code does NOT: the *why*, the *shape*, the *seam between concerns* —
best shown as a figure. Code-as-explanation duplicates the SSOT and drifts.

**How to apply:**
- Explain a mechanism with a **C4/UML figure** (mermaid `flowchart`+`subgraph`,
  per [[c4-diagrams-flowchart-not-native-dsl]]) + prose on the *why*, not a code listing.
- If a concrete symbol matters, name it (`ClassName#method`, a file path) and link —
  don't inline its body.
- A tiny snippet is OK only when the *exact syntax IS the point* (a config key shape,
  a one-line contract) — never a full method/class/script as narrative.
- Retire existing code walls when you touch a doc (they're usually stale too).

See [[spec-figure-first-reading-loop]] (the user reads figure-first) and
[[c4-diagrams-flowchart-not-native-dsl]] (how to draw them).
