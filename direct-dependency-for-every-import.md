---
name: direct-dependency-for-every-import
description: Project rule (user, 2026-06-28) — every imported class MUST come from a DIRECT Maven dependency of the module, never a transitive one. Relying on a transitive jar (e.g. jackson reaching a module only through another module's dep) is a defect to fix, even when it compiles.
metadata:
  type: feedback
---

The user's rule, stated verbatim during world-gateway Option B: *"il faut ajouter la
dépendance en direct, c'est la règle. toutes les classes qu'on importe doivent être tirées
d'une dépendance directe."*

**The rule:** if a module's source imports `com.fasterxml.jackson.core.JsonProcessingException`
or `io.seedmatic.rke2lab.gateway.port.Document`, that module's POM must declare that artifact
**directly** — `jackson-core`, `gateway-port` — at the right scope. A class reaching the
compiler only because some *other* direct dependency happens to drag it in transitively is a
defect, even though `mvn` is green.

**Why:** a transitive provider can vanish on any upstream version bump or refactor, breaking a
module that never changed; direct declaration makes the real surface explicit and reviewable
(it is the POM-level twin of the OSGi seam-purity discipline —
[[document-seam-cannot-expose-jackson-jsonnode]]).

**How to apply:** when you add or move an `import`, grep the module's imports against its POM's
direct deps. For a module that imports both `jackson.core` and `jackson.databind`, declare
BOTH (databind does not re-export core's types as a direct dep — they are separate artifacts).
For a `-test` fragment whose `src/main` bytecode resolves against its host bundle at runtime,
the scope is `provided` (the host carries the bundle); for a normal module it is `compile`.

Caught in Option B: doctor-core had only `jackson-databind` (used `JsonProcessingException`
from `jackson-core` transitively); doctor-core-test declared NEITHER jackson NOR `gateway-port`
yet imported both (leaning entirely on the transitive path through doctor-core/provided). See
[[transitive-import-leaks-doctor-core-test-backlog]] for the records/spi leaks still open there.
