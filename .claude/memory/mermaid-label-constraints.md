---
name: mermaid-label-constraints
description: "Mermaid DSL label constraints that broke a whiteboard render (2026-07-09). The rules to respect when authoring C4/Mermaid figures in .claude/claude-preview.adoc and the specs — chiefly: never nest escaped double-quotes inside a quoted label."
metadata:
  type: feedback
---

The user caught Mermaid render errors in the whiteboard (2026-07-09): "tu ne dois pas avoir lu les
contraintes liées au DSL mermaid." The defect: I put `@DocumentContract(\"readiness-verdict\")` — escaped
double-quotes INSIDE a label already delimited by `"..."`. Mermaid does not parse nested escaped quotes;
the whole diagram fails.

**Rules (verified against the shipped specs' Mermaid, e.g. seed-broker-spec.adoc):**

+ **Never nest `\"` inside a quoted label.** Use single quotes `'...'` for any inner quoting:
  `a2["@DocumentContract('readiness-verdict')"]` — NOT `a2["...\"readiness-verdict\"..."]`.
+ **Always wrap a label in `"..."` when it contains ANY special char** — then `·`, `→`, `*`, `/`, `()`,
  `:` are all safe inside. The specs quote every non-trivial label for this reason.
+ Line breaks in labels: `<br/>` (the specs use it), not `\n`.
+ Edge labels `|"..."|` follow the same quoting rule as node labels `["..."]`.
+ **Never use a Mermaid reserved word as a node ID** — `graph`, `end`, `style`, `class`, `subgraph`,
  `flowchart`. `graph["…"]` breaks the parse (it reads `graph` as the diagram-type keyword expecting
  `graph LR`). Rename the node (`pgraph`, `theGraph`). Caught 2026-07-14 on the cellar whiteboard.
+ **Never put angle-brackets `<...>` in ANY label** — a Java generic like `Consumer<Store>` in a
  sequenceDiagram message (or any node/edge label) is read as an HTML tag and 400s the render (pptr.dev
  "Error 400"). Caught 2026-07-18 on the runbook-handler whiteboard. Drop the type parameter (`seed
  Consumer`) or say it in prose. Same class: sequenceDiagram message text also rejects `()` and `:` —
  write `handle cellar, trigger`, not `handle(cellar, trigger)`. Keep message labels plain words.
+ **Never put a semicolon `;` in ANY label or Note text** — `;` is Mermaid's statement separator, so a
  Note like `Note over X: proceed; rendu pending; RunGate` is parsed as multiple broken statements and
  the diagram errors on that line. Caught 2026-07-19 on the preview whiteboard (Figure 2, a
  sequenceDiagram Note). Use commas or `<br/>` instead. Same rule everywhere a `;` might sneak in
  (long explanatory Notes are the usual offender).

+ **Em-dash `—` breaks a label** — it is NOT in the safe set; use a plain hyphen `-`. Caught
  2026-07-21 (nocloud-preview whiteboard: figures rendered broken). Same event: keep **subgraph
  titles plain ASCII** — a quoted `subgraph "…"` renders, but stuffing it with `—`, `/`, `·` is where
  it broke. Node labels tolerate `·`/`/`/`:` inside quotes; subgraph titles are stricter, so keep them
  short plain words. Rule of thumb now: **mermaid labels = ASCII only** (no `—`, no accented French,
  no `·` unless strictly needed inside a node label), `<br/>` for breaks, "or"/"plus"/"and" instead of
  `/`/`+`/`&`.

**★ VALIDATION IS MANDATORY IN BRAINSTORM MODE (feedback 2026-07-21 — stop the "figures cassées"
round-trips).** Before I tell the user a whiteboard figure is ready, I MUST render-check every Mermaid
block against kroki myself — never present un-rendered figures. Recipe: extract each block to a file
and POST it:

```bash
curl -s -o out.svg -w "%{http_code}" -X POST http://bioskop-nixos.local:8000/mermaid/svg --data-binary @fig.mmd
# fallback / cross-check: https://kroki.io/mermaid/svg
```

`200` = renders. A `400` comes in TWO flavours, and telling them apart is the whole point:
+ *Real syntax error* — the body names a lexer/parse problem (nested `\"`, `<...>`, `;`, reserved-word
  id, `subgraph id["..."]`). FIX the diagram.
+ *Transient SERVER flake* — the body says `Failed to launch the browser process ... Resource
  temporarily unavailable` (kroki's chromium/crashpad). This is NOT my syntax — it hits ALL diagrams
  regardless of size (verified: a 4-node diagram failed 0/4 while a byte-identical block rendered 200
  minutes earlier). RETRY, or switch server.

**Server choice (2026-07-21 finding, refines [[hub:diagram-preview-file]]):** `kroki.io` (online) was the
FLAKY one this session (intermittent chromium-spawn 400s); the LOCAL `http://bioskop-nixos.local:8000`
returned 200 reliably and the user's preview then rendered. So when online kroki flakes, validate
(and point `.asciidoctorconfig`) at the local server. Do NOT keep editing the diagram to "fix" a
server flake — that was the wasted-cycles trap this session.

**How to apply:** when authoring/editing any Mermaid block, grep the diff for `\"` before saving — a
nested escaped quote is the classic breaker. Prefer apostrophes for inner quoting. Then scan for `—`,
`;`, `<...>`, `()` in edge labels, and non-ASCII in subgraph titles. THEN render-check against kroki
(above) before saying it is ready. See [[options-always-as-c4-diagrams]] [[collaborative-design-method]]
[[hub:diagram-preview-file]].
