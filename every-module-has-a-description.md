---
name: every-module-has-a-description
description: "Every reactor module's pom carries its own <description> — good Maven hygiene for ALL modules (bundle or not), and for bundles bnd folds it into Bundle-Description. Born 2026-06-18: a parent's <description> was leaking verbatim into every child bundle's manifest; the fix is per-module descriptions, NOT stripping the header."
metadata:
  node_type: memory
  type: feedback
---

**Rule (user, 2026-06-18): give EVERY reactor module its own `<description>` in its pom** — bundle or
not. It is good Maven hygiene in its own right (the module self-documents), and for an OSGi bundle it
is also load-bearing: **bnd folds `project.description` into the `Bundle-Description` manifest header.**

**Why this rule exists (the bug it prevents).** During the osgi-space-bundles step, the moved bundles'
manifests carried `Bundle-Description: Parent of the OSGi bundles…` — the `<description>` of
`osgi/bundle-parent`. bnd walks the WHOLE parent chain for `project.description`, so a parent's Maven
description leaks verbatim into every child bundle's manifest.

**The WRONG fix (rejected, do not repeat):** stripping the header in the shared bnd config
(`-removeheaders: Bundle-Description`). That deletes a metadata we actually WANT — the target is for
each bundle to HAVE a correct description, not to have none. (User: "la target est d'avoir la
description du bundle dans le manifest, right?")

**The RIGHT fix:** every module declares its own `<description>`. A child's own description overrides
the inherited one → bnd emits the bundle's real `Bundle-Description`; modules with no description were
the only ones leaking the parent's. Verified: each bundle manifest now carries its own line.

**How to apply (a standing checklist item):**
- When CREATING a module pom, add a `<description>` describing the module's role — never leave it to
  inherit a parent's.
- When AUDITING, `for pom in $(find . -name pom.xml -not -path '*/target/*' -not -path '*/src/*'); do
  grep -c '<description>' "$pom"; done` — zero on any module is a gap to fill.
- Parent/aggregator poms (build-parent, bundle-parent, host-parent, the space aggregators) keep their
  description too; the leak is fixed by the CHILD having its own, not by the parent dropping its.

**The broader principle this instance taught (user): widening scope for a justified IMPROVEMENT is
allowed — it is not scope-creep.** This step was "move + bnd-ify the 3 OSGi modules", but documenting
all 14 description-less modules was a coherent transverse improvement, so it rode along rather than
spawning a separate branch. Judgement: an improvement that is uniform, low-risk, and germane to what
you are already touching may widen scope; a different KIND of work (a refactor, a feature) still gets
its own branch. See [[osgi-space-bundles-state]].
