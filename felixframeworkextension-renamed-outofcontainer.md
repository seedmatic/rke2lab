---
name: felixframeworkextension-renamed-outofcontainer
description: "FelixFrameworkExtension was RENAMED to OutOfContainerFrameworkExtension (osgi/junit-testkit, commit a20d2c7c on feature/dbus-systemd-edge). Many older memories + wip specs still say FelixFrameworkExtension — that class no longer exists. The in-framework twin InContainerJUnitRunner is unchanged."
metadata:
  node_type: memory
  type: project
---

## What changed (2026-06-23, commit a20d2c7c)

`osgi/junit-testkit/.../FelixFrameworkExtension` → `OutOfContainerFrameworkExtension`.
Pure mechanical rename (git mv + token replace across call sites, bnd fixture comments, pom
comments, live `docs/architecture/*.adoc`). No behaviour change. Reactor green under `-Pall-worlds`.

**Why:** the two halves of the OSGi test-fragment model were named on different axes — one after
the TECHNO (Felix), one after the PLACE (`InContainerJUnitRunner`). The user's call: name BOTH by
place. The JVM-side half drives the framework from OUTSIDE the container (flat app classpath,
black-box via `awaitService`/`resolve`); its in-framework twin runs INSIDE on the host classloader
(white-box). So `OutOfContainer` ↔ `InContainer` now read as opposites.

**How to apply:** when an older memory or a `wip/specs|plans/` doc says `FelixFrameworkExtension`,
read it as `OutOfContainerFrameworkExtension`. Those historical records were NOT rewritten (they
reflect what was true when written). The class, its `Builder`, `awaitService`, `resolve`,
`installMatching`, `installFixtureWithHost` etc. are otherwise identical.

**The distinction it sharpens** (the user asked twice): `OutOfContainerFrameworkExtension` builds a
topology the TEST declares and sets `system.packages.extra` by hand — it never runs `BootPlanner`
or `HostClassLoaderView`. `EmbeddedBundlesBootTest` boots via `BootPipeline.embedded()` = the PROD
runner, exercising the real boot decision on the real deployed topology. That is why the latter does
NOT use the extension — and why it (not the extension) caught the dbus-java boot-frontier
regression. See [[dbus-systemd-edge-spec-state]].
