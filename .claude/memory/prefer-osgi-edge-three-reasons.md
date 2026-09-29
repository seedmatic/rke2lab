---
name: prefer-osgi-edge-three-reasons
description: "Three POSITIVE reasons to materialise a playable external edge in the OSGi world rather than host — explicit frontier, inherited SCR IoC, and free fail-fast — surfaced building ssh-to-age-edge. Playability only ENABLES the choice; these are why to MAKE it."
metadata:
  node_type: memory
  type: feedback
---

When an external edge's contact is *playable* (pure JDK — `ProcessBuilder`, `java.nio` — runs inside
Felix), playability merely *enables* putting the edge in the OSGi world; it is not itself a reason to.
Building `ssh-to-age-edge` (the first edge materialised in OSGi, not host) surfaced three POSITIVE
reasons, and the user pushed on each — they are why one should PREFER OSGi once the consumer is there:

1. **Explicit frontier.** The edge is the system's skin made a named module, regardless of world.

2. **Inherited IoC.** In OSGi you get the container's inversion-of-control for free: SCR does discovery
   + injection + lifecycle (`@Component` / `@Reference`). A host edge is assembled by hand — a `new
   EdgeImpl(...)` in some assembly that also owns wiring and lifecycle. The OSGi edge just *appears* in
   the service by injection; no factory, no registry code written.

3. **Free fail-fast.** Because the injection is container-managed, the `@Reference` can be *mandatory*:
   no edge ⟹ the component never activates ⟹ its service never publishes ⟹ a hard resolution/boot
   failure, not a silently half-done result. A host edge has neither the injection nor the guard — you
   write the null-check yourself, or forget it (the errors-as-logs trap, see
   [[hub:error-handling-layered-contract]]).

**The load-bearing link:** reasons 2 and 3 are two faces of ONE mechanism — container-managed injection
is what *lets* the reference be mandatory, which is what *makes* "edge missing" a resolution failure
instead of a runtime bug. They are not independent perks; the IoC buys the fail-fast.

**Corollary already in the model:** an edge's world is DERIVED from its consumer's world. ssh-to-age is
OSGi because `DefaultManifestSynthesisService` is a `@Component`; pulumi/dbus stay host because their
callers are host (non-playability is only the *secondary* reason their impl could not move anyway).

See [[external-edges-chantier-handoff]], `docs/architecture/patterns/port-edge-domain-ownership.adoc`
(the two resolution regimes), [[hub:error-handling-layered-contract]] (why silent-inactive is the
anti-pattern fail-fast avoids).
