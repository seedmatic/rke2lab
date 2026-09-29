---
name: bnd-annotations-spike-state
description: "Spike (worktree spike/bnd-annotations, off design/target-module-layout) reworking the osgi-bench so JAVA ANNOTATIONS are the source of truth and bnd GENERATES manifest headers + Metatype XML — instead of hand-written Require/Provide-Capability + hand-written OCD XML with empty *Component.java shells. DONE 2026-06-18: BOM pins the three *.annotations artifacts (osgi.cmpn:8.1.0 absent from central), osgi-bench rewritten so each bundle declares HONESTLY: host=@Capability (provides), config=@RequireServiceComponentRuntime+@RequireMetaTypeExtender (requires — NOT @Component, which would lie that config is a DS component + emit a dead OSGI-INF descriptor), schema=@ObjectClassDefinition(pid=…) (describes). All bnd.bnd identity-only, schema.xml deleted, P1+P2 green. Born reprising slice 2 ([[step2-decomposition-state]])."
metadata:
  node_type: memory
  type: project
---

## Why this spike exists (the diagnosis)

Slice 2 built the OSGi bench (`osgi-bench/`, green) but the bench proves only that the
Felix resolver WORKS — NOT that the Java code is the source of truth. Today the contract is
**hand-written in the manifests; the Java is empty shells**:
- `osgi-bench-config/bnd.bnd` — `Require-Capability osgi.extender` typed by hand.
- `osgi-bench-host/bnd.bnd` — `Provide-Capability osgi.extender` typed by hand.
- `osgi-bench-schema/bnd.bnd` + `src/main/resources/OSGI-INF/metatype/*.xml` — Metatype OCD typed by
  hand.
- All three `*Component.java` are empty shells whose Javadoc literally says "its only role is to
  carry the header bnd emits".

This VIOLATES the recorded discipline [[hub:check-osgi-standard-before-modeling]] ("APPLY OSGi's
principles, don't bypass them"): we recopied by hand what OSGi GENERATES from Java annotations.
User's words: "le code java doit être la principale source of trust".

## The annotations we MISSED (verified on the standard, osgi.cmpn 8.1.0 javadoc, not on ~/.m2)

The `org.osgi.service.*` jars on disk carry only the RUNTIME API. The CONSTRUCTION annotations bnd
reads to generate manifest + XML live in SEPARATE `.annotations` packages we never added. We had
ONLY `osgi.annotation` 6.0.1 (just `@ProviderType`/`@ConsumerType`/`@Version`).

**DS — `org.osgi.service.component.annotations` v1.5 (the central case — user: "genre Component :)"):**
- `@Component` — marks the class a component; generates `OSGI-INF/<comp>.xml` AND (meta-annotated
  `@RequireServiceComponentRuntime`) makes bnd emit `Require-Capability osgi.extender=osgi.component`.
- `@Activate`/`@Deactivate`/`@Modified`, `@Reference` (+ enums ReferenceCardinality/Policy/ServiceScope),
  `@RequireServiceComponentRuntime`, `@ComponentPropertyType`. `ConfigurationPolicy.REQUIRE` = the
  loud-fail-on-missing-config (activation plane, stage 4).

**Metatype — `org.osgi.service.metatype.annotations` v1.4:**
- `@ObjectClassDefinition` — generates the Metatype XML (replaces the hand-written schema.xml).
- `@AttributeDefinition` — one attribute (type/cardinality/required/default); what `InfraDomain` reinvents.
- `@Designate` — binds OCD to a PID. `@RequireMetaTypeExtender` — emits `Require osgi.extender=osgi.metatype`.
- `@Icon`, `@Option`, `@RequireMetaTypeImplementation`.

**Bundle — `org.osgi.annotation.bundle`:** `@Capability`/`@Requirement`/`@Header`/`@Export` — the
STANDARD Java form of an arbitrary Provide/Require (the right way for host to declare it provides the
extender, vs the text header).

**Already on disk:** `org.osgi.service.cm.annotations` → `@RequireConfigurationAdmin` (delivery, stage 3, not the bench).

Other compendium `.annotations` packages exist (event/cdi/configurator/jakartars/jpa/servlet/typedevent/feature)
— NOT needed for this spike.

## STATUS — DONE 2026-06-18, proof holds (P1+P2 green, all headers bnd-generated)

All three steps executed; success criterion met. Specifics worth keeping:

1. **BOM** (`bom/pom.xml`): `org.osgi:osgi.cmpn:8.1.0` is **ABSENT from Maven Central** (`dependency:get`
   failed → confirmed, not on disk either). So pinned the **three `*.annotations` artifacts
   individually** (the recorded fallback): `org.osgi.service.component.annotations:1.5.1` (DS),
   `org.osgi.service.metatype.annotations:1.4.1` (Metatype), `org.osgi.annotation.bundle:2.0.0`
   (Bundle `@Capability`). All three resolve from central. Versions as properties, deps in
   dependencyManagement.
2. **osgi-bench poms** reference them scope `provided`: config → DS+metatype, host → bundle, schema →
   metatype.
3. **Rewrite** — each `*Component.java` now carries the real annotations; every `bnd.bnd` is
   identity-only (`Bundle-SymbolicName`/`Export-Package`/`-noimportjava`), no hand-typed capability
   lines, hand-written `schema.xml` DELETED. The bnd-GENERATED forms that worked:
   - **host** = `@Capability(namespace="osgi.extender", name=…, version=…)` ×2 (NOT `@Component` — host
     advertises a capability it owns, it is not a DS component). Emits `Provide-Capability`.
   - **config** = `@RequireServiceComponentRuntime` + `@RequireMetaTypeExtender` (NOT `@Component`).
     config REQUIRES the DS extender; it is NOT a DS component. `@RequireServiceComponentRuntime` is
     itself a `@Requirement(osgi.extender=osgi.component, version=1.5)` and the standard says it
     "can be used directly" by a bundle that needs DS processing — it emits the `Require
     osgi.extender=osgi.component` with NO `Service-Component`/`OSGI-INF` descriptor. `@Component`
     would emit the SAME require (it is meta-annotated with `@RequireServiceComponentRuntime`) BUT
     also declare the class a component → a `Service-Component:` header + `OSGI-INF/<class>.xml` for a
     component this DS-free bench has no runtime to activate (a lie in the bundle — the very
     hand-typed-header anti-pattern, just relocated into an annotation). Symmetric to the metatype
     side: `@RequireMetaTypeExtender` (require) vs `@ObjectClassDefinition` (declare). bnd tightens the
     range to `>=1.5.0 & <2.0.0` (stricter than the old hand-typed `>=1.5`).
   - **schema** = `@ObjectClassDefinition(id=…, pid=…, name=…)` + `@AttributeDefinition` (`required=false`
     for optional) + `@RequireMetaTypeExtender`. **KEY GOTCHA:** use the `pid` ELEMENT on
     `@ObjectClassDefinition`, NOT the `@Designate` annotation — `@Designate` needs a `@Component` to
     derive its PID, which would inject an `osgi.component` require the DS-free P2 framework can't
     satisfy. The `pid` element makes bnd emit the `<Designate>` with no DS dependency. A standalone
     `@Designate` (no `@Component`) emits the OCD but NO `<Designate>` → P2 fails `No
     ObjectClassDefinition for id=…`. Also drop the `-includeresource` line, else the old XML ships
     alongside the generated one.

## Success criterion (the proof)

`ExtenderContractSpikeTest` (P1 resolve/refuse) + `MetatypeIntrospectionSpikeTest` (P2 typed OCD by PID)
stay GREEN while NO `Require-Capability`/`Provide-Capability`/Metatype XML is written by hand anymore —
verified by `unzip -p <bundle>.jar META-INF/MANIFEST.MF` showing bnd-GENERATED headers, and the OCD XML
present under `OSGI-INF/metatype/` generated from annotations. Build: FULL `clean package -Posgi
-Dmaven.build.cache.skipCache=true` ([[build-verification-gotchas]]; partial `-pl` gives false failures).

## OPEN questions — ALL RESOLVED in the spike

- How to emit `Require osgi.extender=osgi.component` WITHOUT lying? **`@RequireServiceComponentRuntime`
  used directly** (the standard sanctions this: it "can be used directly" by a bundle that needs DS
  processing). Verified on the generated MANIFEST: the require stays, and NO `Service-Component`/`OSGI-INF`
  descriptor is emitted. `@Component` also emits the require (meta-annotated with the same requirement)
  but ADDITIONALLY declares the class a DS component — wrong for a bundle that only requires the extender.
- `host` as `@Component`-that-provides vs explicit `@Capability`? **`@Capability`** — host advertises an
  arbitrary capability it owns; it is not itself a DS component. Verified: emits `Provide-Capability`.
- Felix DS runtime (`org.apache.felix.scr`) NOT in BOM — **still not needed.** P1 is pure resolution; P2
  starts only felix.metatype + felix.log. No test activates a DS component, so no SCR runtime required.
  This is WHY schema must avoid `@Component`/`@Designate` (see step 3 gotcha above).

## Workspace / method

- Worktree `rke2lab.d/spike/bnd-annotations`, branch `spike/bnd-annotations`, base =
  `design/target-module-layout` (NOT main — the bench lives on our branch, not yet on main). sops
  re-smudged (keys.yaml; keys.schema.yaml ENC[ = false positive, it's a JSON-Schema comment).
  `.code-workspace` sibling created at `rke2lab.d/spike/bnd-annotations.code-workspace`.
- Merge plan: SQUASH back into `design/target-module-layout` once the proof holds (solo, no PR —
  [[rke2lab-solo-no-pr-merge-direct]]). Disposable bench = scaffolding; production config (`InfraDomain`)
  adopts the proven annotation pattern LATER by MOVE.
- All Maven through `flox activate -- ./mvnw …`.

See [[step2-decomposition-state]] (the parent chantier + the 4-plane / static→dynamic roadmap),
[[hub:check-osgi-standard-before-modeling]] (the meta-lesson driving this), [[osgi-test-in-vscode-three-ways]].
