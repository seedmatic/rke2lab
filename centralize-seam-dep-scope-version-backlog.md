---
name: centralize-seam-dep-scope-version-backlog
description: BACKLOG (user-deferred, "polish dependencies later") — centralize the world-gateway dependency scope AND the ${project.version} of our own modules in dependencyManagement/BOM, so child poms declare only groupId+artifactId. NOT gateway-document-codec (its scope is non-uniform per realm). Raised during world-gateway 2D T5.
metadata:
  type: project
---

## The goal (user, 2026-07-01, during 2D T5)

The user wants to stop repeating `<scope>provided</scope>` and `<version>${project.version}</version>`
in every child pom that depends on our OSGi seam artifacts. Manage them once in a parent
`<dependencyManagement>` (and/or the `bom/` module) so children declare just groupId+artifactId.

## What CAN be centralized

- **`world-gateway` scope** — uniformly `provided` in every realm (system-exported OSGi-side; present
  in the host uber-jar). One managed entry `provided`; children drop the scope. ✅
- **`${project.version}` of our own modules** — the standard BOM pattern; `bom/pom.xml` already exists
  and imports other BOMs (`<scope>import</scope>`). Add our modules' versions there so children drop
  `<version>`. ✅

## What must NOT be centralized

- **`gateway-document-codec` scope is NON-uniform**: the host SHADES it (compile/runtime, to bundle it
  flat) while OSGi NESTS it via `-includeresource;lib:=true` and resolves it through the doctor-core
  host bundle (`provided`). A single managed scope would break one realm. Keep per-module.
  See [[nesting-our-own-flat-module-per-realm]].

## Status

DEFERRED by the user ("let polish the dependencies later") to keep 2D moving. Do after the 2D arc
(T5-T10) lands. See [[world-gateway-2d-execution-state.md]].
