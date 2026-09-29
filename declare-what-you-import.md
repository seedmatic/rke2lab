---
name: declare-what-you-import
description: "Maven hygiene the user insists on: a module that imports a package/class in its code MUST declare that dependency explicitly in its pom — never lean on a transitive dependency for types you import directly. Surfaced during doctor Move A (2026-06-21): after relocating value types to doctor-port, seed-master imported doctor.port.* but only declared doctor-core; fixed by declaring BOTH doctor-port and doctor-core, and flipping the testkit core→port."
metadata:
  type: feedback
---

When a module `import`s a package/class, that dependency is **explicit** and must appear in the
module's `pom.xml`. Do NOT rely on it arriving transitively (e.g. seed-master imports `doctor.port.*`
but only declared `doctor-core`, getting `doctor-port` transitively).

**Why:** it is the "used but undeclared" defect `mvn dependency:analyze` flags, and it is fragile — the
day the intermediate dependency stops re-exporting (here: if `doctor-core` ever stops depending on
`doctor-port`), the importing module breaks without having changed. The pom must state the true
compile-time surface.

**How to apply:**
- After any refactor that moves types across modules, re-derive each consumer's real imports
  (port vs core) and reconcile the pom — add the now-directly-imported module, and DELETE any module
  whose types are no longer imported (a dependency can FLIP: the testkit went `doctor-core` →
  `doctor-port` entirely because its only doctor imports became port types).
- Verify with `dependency:analyze` (offline needs `-am` so reactor siblings resolve, and a prior
  `package` so packaging goals like `stage-embedded-bundles` don't fail resolving sibling jars; or use
  `dependency:analyze-only` after `package`). Confirm zero target-module lines in the
  used-undeclared / unused-declared blocks.

Related: [[doctor-internal-edge-debt]], the Placement 2 plan (`.claude/plans/doctor-internal-edge-placement2-plan.md`).
