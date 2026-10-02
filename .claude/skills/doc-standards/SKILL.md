---
name: doc-standards
description: >-
  Use when writing or updating architecture/design documentation in the rke2lab
  repo (AsciiDoc under docs/). Covers when to document (proactively — on a new
  subsystem, a non-obvious design decision, a reusable pattern, or a phase
  handoff), the quality standard (Overview, C4 Mermaid diagrams, usage patterns
  with ✅/❌ anti-patterns, setup, troubleshooting, related-docs), the
  AsciiDoc + Mermaid format and style rules, bidirectional cross-referencing plus
  updating docs/README.adoc, and the pre-completion documentation checklist.
  Triggers on "document this", creating or editing a .adoc file, adding an
  architecture doc, or completing a subsystem that hands off to future work.
---

# Documentation standards (rke2lab)

Documentation is critical for context recovery. rke2lab is a complex,
multi-concern project (Incus, Kubernetes, Cluster API, GitOps, systemd,
networking) where you frequently context-switch between domains. High-quality
docs prevent re-learning and prevent architectural mistakes during future work.

## When to document

Document architectural decisions, patterns, and workflows **proactively** —
don't wait to be asked — especially when:

- implementing a new subsystem or cross-cutting concern,
- making a non-obvious design decision (e.g. "why not constructor parameters?"),
- establishing a pattern that will be reused (e.g. manifest unit access patterns),
- completing a phase or deliverable that hands off to future work.

If the implementation revealed complexity or needed clarification mid-flight,
that's the signal to document.

## Quality standard

Follow `docs/architecture/bootstrap/bootstrap-identity-provider.adoc` (commit
`c324fa05`) as the reference. Required elements:

1. **Overview** — what is this, why does it exist, what problem does it solve?
2. **C4 architecture diagrams** (Mermaid): a context diagram showing separation
   of concerns; a component or sequence diagram showing data flow; a color-coded
   legend explaining component types.
3. **Usage patterns** — the correct pattern with code examples, anti-patterns
   called out (❌ don't / ✅ do instead), and *why* the correct one is better.
4. **Setup / configuration** — step-by-step bootstrap instructions if applicable.
5. **Troubleshooting** — common errors with causes and fixes.
6. **Related documentation** — bidirectional cross-references.

## Format and style

- **AsciiDoc** (`.adoc`), for consistency with existing docs.
- **Mermaid** for diagrams (not PlantUML).
- Code examples should be runnable and match the actual implementation.
- Organize **by concern**, not chronologically.
- Clear section headers — the reader should find what they need quickly.

## Cross-referencing discipline

When you create or update a doc:

1. add forward links FROM your doc TO related docs,
2. add backward links FROM related docs TO your doc,
3. update `docs/README.adoc` with your doc in the appropriate section,
4. add navigation hints if your doc is part of a learning flow.

Cross-reference block at the end of a document:

```asciidoc
== Related Documentation

* link:other-doc.adoc[Other Doc] - Brief description of relationship
* link:another-doc.adoc[Another Doc] - Why the reader might go there next
```

## Documentation checklist

Before considering architecture work complete:

- [ ] Core concepts explained with "why", not just "what"
- [ ] Anti-patterns called out (prevents re-making the mistake)
- [ ] C4 diagram showing concerns and data flow
- [ ] Code examples demonstrate actual usage
- [ ] Bidirectional cross-references to related docs
- [ ] `docs/README.adoc` updated with the new document
- [ ] Troubleshooting section with common errors
- [ ] **Validated** — `asciidoctor` clean and every Mermaid block parses (below)

## Validating before you commit

Both CLIs come from the **fleet's shared `asciidoc` env** (`.flox.d/asciidoc`, included by
this repo's manifest since 2026-09-29) — `asciidoctor-with-extensions` and `mermaid-cli`.
Nothing here renders docs for publication; it is a *syntax gate*, because a malformed
table or a Mermaid parse error is invisible until a human opens the file.

```bash
# 1. AsciiDoc structure: malformed tables, unclosed blocks, dangling xrefs.
#    Silence = clean.
flox activate -- asciidoctor -o /dev/null docs/architecture/<area>/<doc>.adoc

# 2. Mermaid: extract each [mermaid] block and parse it.
flox activate -- mmdc -i block.mmd -o /tmp/out.svg
```

No setup is needed for `mmdc`: it drives a headless browser via puppeteer, and the
`asciidoc` env's `on-activate` hook points `PUPPETEER_EXECUTABLE_PATH` at whatever
`chrome-headless-shell` is cached under `~/.cache/puppeteer` (nixpkgs ships no chromium
for aarch64-darwin, and puppeteer otherwise aborts naming a version it never downloaded).
If `mmdc` complains it cannot find chrome, nothing is cached — that is the one case
needing `npx puppeteer browsers install chrome-headless-shell`.

⚠️ **`asciidoctor` does NOT validate Mermaid.** Without the diagram extension a
`[mermaid]` block is just a listing, so it passes while the figure is broken. The two
checks are independent — run both.

### ❌ Never put `&lt;placeholder&gt;` in a Mermaid diagram

Measured 2026-09-29: Mermaid decodes HTML entities *before* parsing, so `&gt;` becomes
a `>` that reads as an arrow token — a parse error, and quoting the label does not help.
Three figures in `docs/` were silently broken this way.

```
❌ participant G as git (manifests/&lt;cluster&gt;)   → Parse error on line 4
✅ participant G as git (manifests/{cluster})
```

Parentheses are fine; only the entities break. Use `{name}` for placeholders.

### The four Mermaid traps measured on 2026-09-29

A sweep of all 170 `[mermaid]` blocks in `docs/` found **six broken figures** — including
one in this skill's own reference doc. Four distinct causes, all silent:

| Trap | ❌ Breaks | ✅ Write instead |
|---|---|---|
| HTML entity placeholder | `participant G as git (manifests/&lt;cluster&gt;)` | `participant G as git (manifests/{cluster})` |
| bare `;` anywhere in a sequence line | `Note over S: a (gated; reads) b` | `Note over S: a (gated — reads) b` (or the `#59;` escape) |
| `()` in an EDGE label | `Units -->\|reads via current()\| Context` | `Units -->\|"reads via current()"\| Context` |
| `()` in a `subgraph` label | `subgraph AFTER[After — k8s (FloxEnv CRD)]` | `subgraph AFTER["After — k8s (FloxEnv CRD)"]` |

The rule behind all four: **quote any label carrying punctuation**, and never let a `;`
or a decoded `>` reach the parser. Parentheses in a *node* label (`X["a (b)"]`) and in
sequence *message* text are fine — it is edge and subgraph labels that need the quotes.

### Also caught by the `asciidoctor` gate

* A `|` inside a table cell — even within backticks — is read as a **cell separator**, so
  asciidoctor silently DROPS cells (`dropping cells from incomplete row`). Escape it:
  `` `git show … \| yq .facet` ``.
* A closing `====` joined to the following paragraph (`==== The RunbookEnvelope keeps …`)
  becomes a level-4 *heading*, so the admonition never closes and swallows the prose after
  it. Both were live in `docs/` and invisible until the gate ran.

## Why this matters

The bootstrap identity provider doc prevented a near-duplication where
constructor parameters were about to be added to manifest units — the doc made it
clear that constructor params for runtime config are wrong (statically
instantiated units can't receive them) and context access via `bootstrapIdentity()`
is right (ThreadLocal injection). The mistake was caught at design time instead of
during synthesis. High-quality docs let you leave a subsystem and return weeks
later without re-learning, and make code review effective by exposing the *why*.
