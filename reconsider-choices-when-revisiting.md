---
name: reconsider-choices-when-revisiting
description: Working principle — when revisiting committed code, re-question the original choice; don't preserve it on inertia or because an error symptom once guided it.
metadata:
  type: feedback
---

When an increment revisits an existing committed decision, **re-question the decision itself** —
do not preserve it by inertia, and do not assume the original fix was right just because the build
is green. The user (2026-06-30): "il faut toujours re-considerer nos choix quand on repasse dessus.
on peut facilement aller vers la facilite, ou etre guide par les symptomes des erreurs, ici un
class cast je pense."

**Why:** the easy path is to keep the old arrangement and bolt the new rule on top; and many old
arrangements were shaped by a past error *symptom* (e.g. a `ClassCastException`/`LinkageError` from
two-realm class duplication) rather than by the right design. The symptom-driven fix can be locally
correct yet hide a question never asked.

**How to apply:** when a task touches committed code, spend a probe asking "is this still the right
choice, or a symptom-driven leftover?" — and bring the finding to the user as a fork, not a silent
decision. Two outcomes are both valid: (a) the old choice was genuinely right (verified 2026-06-30:
cdk8s IS used host-side — IncusResourceBootstrap builds org.cdk8s.App at compile scope to synthesize
incus host-slot manifests — so flat∧staged is a legitimate dual-realm case, not a scope leftover), or
(b) it was a leftover to remove. The point is to *verify which*, at the source, before acting. Pairs
with the established lesson: when a finding contradicts a green build, check the COMMITTED state
(git show HEAD:path), not a second-hand summary.
