---
name: collaborative-design-method
description: "The reasoning method that made the 2026-07-06 ClusterSeed/socle design session work — the user explicitly asked to preserve it across compaction and keep collaborating at this level. Verify at the source (never from memory), figure-first with a recommendation, challenge both the user's premise AND my own with no default winner, name by anchoring on existing reality (invent nothing), think source-vs-projection, keep reserves honest, don't overbuild."
metadata:
  type: feedback
---

The user loves this working method and asked to keep reasoning this way. Apply it for all substantive
design/architecture work on this project.

**Why:** it repeatedly turned my half-right proposals into correct ones — the user's questions caught real
defects (RunMode over-specified, Pulumi leaking into the domain, `Realm` name's security connotation,
`BootedFramework` conflating boot+access) that I would have shipped. The method is what produces quality.

**How to apply — the moves, in order of reflex:**

1. **Verify at the source, never from memory.** Before asserting how jGiven/JUnit/OSGi/Plexus behaves,
   READ the actual jars/sources (extract to /tmp), the actual code, the actual poms. Every load-bearing
   claim this session was checked against bytecode/source — and several of my from-memory beliefs were
   wrong (ScenarioHolder null timing, DiscoverySelectors package, standalone=live). "Vérifié, pas supposé."

2. **Figure-first.** The user reads figures, prose is the verification key. Put every fork in
   `.claude/claude-preview.adoc` as a C4/Mermaid diagram, MY recommendation in the green box, before
   asking. They react on figures. (cf. [[options-always-as-c4-diagrams]])

3. **Challenge both premises — no default winner.** When there's a drift/disagreement, we decide
   case-by-case who's right: sometimes the code, sometimes the spec, sometimes the user, sometimes me.
   I must contradict the user when the source says so (I did, several times) AND self-correct when they
   catch me (I did, more times). Neither "the user is always right" nor "the code is always right".

4. **Name by anchoring on existing reality — invent nothing.** Best names were FOUND, not coined:
   `ClassRealm` (Plexus), "world" (already in the code's javadocs), `RunMode` states (Pulumi's own
   contract). When naming, first ask "what does this ALREADY get called, here or in the ecosystem we
   live in?" (cf. [[single-source-of-truth-before-logic]])

5. **Source-vs-projection thinking.** The recurring winning shape: one FACT is the source (RunMode, the
   ClassRealm/world), everything else is a PROJECTION that must not re-decide in its corner (LiveGate,
   probes, executor; the capability faces). Reach for this when something is "decided in several places".

6. **Honest reserves, always.** Distinguish my typo from a real drift. Flag risks as risks ("à vérifier
   à l'implémentation"). Say when I might be wrong and want their view. Report faithfully (real test
   output, not "should pass").

7. **Don't overbuild; YAGNI with the door tooled.** An abstraction needs a real 2nd client before
   extraction. Expose only what a client asks for now; leave extension tooled (e.g. `adapt(NewFace)`)
   for later. The user reasons this way and likes it named back.

8. **Progress narration** ([[handoff-prompt-opens-on-progress-narration]]): one line before each tool
   batch, live todo, never silent tool chains.

**Standing:** keep collaborating at this level of depth for design questions — the user WANTS the long,
careful, figure-first, source-verified exchange, even when it means suspending the current increment to
get a cross-cutting design (like ClassRealm) right first.
