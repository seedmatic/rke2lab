---
name: m2-snapshot-masking-is-critical
description: "WHY the user treats a stale SNAPSHOT in ~/.m2 as a CRITICAL hazard, not a tidiness issue — and why a green build that depends on a frozen local jar is a LIE worth machine-blocking. Born 2026-06-19 when purging ~/.m2 surfaced a latent osgi/testkit relativePath bug masked by a frozen build-parent."
metadata:
  node_type: memory
  type: feedback
---

The user has a hard-won scar here: a past masked problem that was **graver** than a broken
`relativePath`, and which cost him real energy **convincing other people it was a critical bug at all** —
because "it builds on my machine" makes the person who is RIGHT carry the burden of proof. A stale
artifact frozen in `~/.m2` is doubly insidious: (1) it hides the incoherence (the build goes green
against yesterday's frozen jar instead of the real sources), and (2) when someone finally notices, the
green build becomes social cover for the bug — you have to fight to be believed.

And the killer compounding move, the one the user lived: the others answer **"tu n'as qu'à le remettre,
tu sais comment faire, c'est pas grave"** — just re-`install` it, no big deal. That reflex is exactly
what turns a critical bug into permanent debt: the workaround (`mvn install`) is so cheap it becomes the
default answer, and every re-install RE-MASKS the bug instead of fixing it. "C'est pas grave" is false
twice — it is not harmless (it hides a real incoherence), and "just put it back" is not a fix, it is
replanting the bomb. The EASE of the workaround is precisely what blocks the real fix. This is why the
machine guard is the right SOCIAL answer, not just a technical one: it REMOVES the "just re-install it"
option — nobody can say "remets-le" because the install fails. The "is it serious?" argument never has
to be had; the mechanism settles it.

**Why this matters / how to apply:**

- Treat "a SNAPSHOT of this project sits in `~/.m2`" as a CRITICAL hazard, never a cosmetic one. A build
  that resolves a sibling from a frozen local jar is a LIE, and lies that look green are the expensive
  kind.
- The fix is to make the lie IMPOSSIBLE, not to detect it after the fact: the `no-snapshot-install`
  enforcer guard ([[osgi-baseline-install-discipline]]) means `~/.m2` can never freeze a project
  SNAPSHOT, so a passing build passes for the right reasons. No more "convince the others" — the
  mechanism removes the burden of proof.
- Corollary discipline: periodically PURGE `~/.m2` of project SNAPSHOTs and rebuild clean from the
  reactor (`-am`). When that surfaces a break, that is the guard WORKING — it found a masked bug early,
  with context in hand, instead of six months later on a mysterious clean-CI failure. Don't read such a
  break as "the purge caused it" — the purge revealed it.
- This is the experiential root under CLAUDE.md's "never install to ~/.m2" rule and
  [[build-verification-gotchas]] ("green build LIES"). When tempted to `mvn install` to work around a
  stale sibling, STOP — that is the exact move that plants the bomb.

## The generalised principle (the real lesson, beyond this one bug)

You cannot *explain* this danger to someone who loves shortcuts — the masked incoherence is invisible by
construction, so for them the shortcut just WORKS, right now, in front of them. The argument asks them to
weigh an imagined future cost against a present, tangible convenience, and it loses every time. So:
**when an argument has to be re-won against convenience on every occasion, the answer is no longer an
argument — it is a guardrail.** A guardrail convinces nobody; it makes the shortcut impossible, which
makes the debate moot. The shortcut-lover does not change his mind — he hits the failing install, reads
the message, and does the right thing without ever being persuaded. Mechanism replaces persuasion. THIS
is why the user wanted the enforcer wired *before*, not a best-practices note: a note is an argument you
must keep winning; the guard is a fact you win once. (Sibling reasoning to `-Werror`/`@NonNullByDefault`
in [[java-cleanup-backlog]] — discipline you machine-enforce instead of repeating.)

And the loop closes with the cruellest irony, the one the user named: **the same person who says "just
re-install it, no big deal" is the one who LATER blames you that "it doesn't work in automated/CI".** Of
course it doesn't — the automated environment is CLEAN, it has none of the frozen `~/.m2` jars that
masked the bug on his machine. So the masking does not merely hide the bug: it TRANSFERS THE BLAME onto
whoever has a clean environment (CI, you, the next person who clones). The one who planted the bomb
accuses the one it explodes on. This is the decisive argument for the guard, because it turns the
shortcut-lover's own demand against itself: "you want it to work in automated? then you want it to work
on a clean `~/.m2` — which is exactly what the guard enforces." The clean CI is not the enemy, it is the
TRUTH; the dev machine with the polluted cache is the lie. Build the truth into the dev's own build so
the two can never diverge.

See [[osgi-baseline-install-discipline]] (the machine guard + the deliberate-install model),
[[build-verification-gotchas]] (green-build-lies, reactor-only resolution), [[java-cleanup-backlog]]
(the 2026-06-19 testkit relativePath fix the purge surfaced).
