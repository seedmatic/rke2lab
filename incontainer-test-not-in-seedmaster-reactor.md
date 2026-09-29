---
name: incontainer-test-not-in-seedmaster-reactor
description: "-bdd-test fragments are NOT Maven deps of seed-master — build them from root or target them explicitly, else surefire never sees them"
metadata: 
  node_type: memory
  type: project
  originSessionId: 2f937488-ea11-441b-b7a7-f56cb85ed71a
---

The `*-bdd-test` modules are OSGi FRAGMENTS (Fragment-Host on the `-bdd` bundle), not Maven dependencies of `seed-master`. So `./mvnw -pl :seed-master -am …` NEVER pulls them into the reactor — its `-am` closure is seed-master's real deps, and the `-test` fragments are not among them. They only build when the reactor includes them:

- a build from the REPO ROOT (`osgi` aggregates them recursively), or
- targeting each explicitly: `./mvnw -pl :manifests-bdd-test -am …`.

**Why it matters:** a green `-pl :seed-master -am` build is NOT evidence the in-container scion proofs pass — surefire never ran them, they weren't in the reactor. It is not surefire skipping tests; the modules are simply absent from the built set. To validate a scion's in-container test, target its `-bdd-test` module (with `-am`, per the always-`-am` rule) or build from root. Hit 2026-07-14 during I3 (manifests host-manifest). See [[jgiven-state-resolved-by-type]].
