---
name: codec-foundation-single-exporter-when-needed-backlog
description: RESOLVED (world-gateway 2D T5, 2026-07-01) — gateway-document-codec became an autonomous FOUNDATION dual-realm bundle (embed; type=library), single exporter of io.seedmatic.rke2lab.world.gateway.codec, imported by any OSGi domain + shaded flat host-side. doctor-core no longer nests/owns it. NOT an OSGi fragment of jackson (it depends on the seam + must be dual-realm + owns its own export). See [[nesting-our-own-flat-module-per-realm]].
metadata:
  type: project
---

## RESOLVED — promoted at T5 (not deferred)

The user chose to fix the ownership tension AT T5: the codec became an autonomous `embed;
type=library` bundle (new staging category, jackson's dual treatment for our code). Single exporter,
any domain imports it, still shaded flat host-side. doctor-core no longer nests or owns it.

Naming settled: kept `gateway-document-codec` (named by its ROLE — encoding gateway Documents — not
by its jackson mechanism, which is a volatile impl detail). It is NOT a jackson OSGi fragment: (1) it
depends on the world-gateway seam, not just jackson; (2) "fragment" has no meaning in the flat host
realm, but the codec is dual-realm; (3) a fragment would merge `world.gateway.codec` into jackson's
export surface — wrong ownership. The jackson EXTENSION is only the internal `WireEnumModule` (a
`SimpleModule`); the bundle is a jackson CLIENT. The text below is the original reasoning.

---

## The tension the user named (2026-07-01, during 2D T5)

`gateway-document-codec` is foundation, multi-realm: the host and ANY OSGi domain may need to
serialize a Document payload. Today it is nested-private into `doctor-core`'s Bundle-ClassPath
(`-includeresource;lib:=true`) + shaded flat into the host. That makes doctor-core look like the
"owner" of a foundation concern — a latent tension if a second OSGi domain (cluster-core,
systemd-core) ever needs the codec OSGi-side.

## Why it is NOT on the gateway seam surface (settled — this is correct)

The codec is to the gateway what jackson is: a dual-realm TRANSPORT tool (two copies, each bound to
its realm's jackson), used INSIDE realm-local implementations, NEVER on a seam verb signature. The
dependency is one-way `codec → seam` (the codec references the seam's wire-records + `WireEnum`);
the seam never references the codec. So the rule "no dual-realm class on the gateway interface"
holds: the codec is dual-realm but not on the interface. See [[nesting-our-own-flat-module-per-realm]].

## The mistake to NOT repeat (cost a red build in T5)

EXPORTING `io.seedmatic.rke2lab.world.gateway.codec` from doctor-core turned a nested-private package
into a bundle-export → the gate classes it "bundle-only" → the flat host referencing it becomes a
REALM_BOUNDARY host/seam leak, AND its package-private `WireEnumModule` trips SPEC_COVERAGE. Keep the
codec nested-private (not exported), exactly like jackson/cdk8s carriers.

## The resolution when a 2nd OSGi consumer appears (the real "single exporter")

Promote the codec to ONE foundation bundle in the OSGi realm: it imports the OSGi jackson, EXPORTS
`world.gateway.codec`, and every OSGi domain IMPORTS it (one exporter, no split). Still shaded flat
host-side. Two copies PER REALM (jackson's granularity), never per domain. Until then: nested-private
in doctor-core. A `-test` fragment that shares its host's loader reaches the nested codec at runtime
and must NOT drive an export — assert on the seam String, or use the codec without an OSGi import.
See [[centralize-seam-dep-scope-version-backlog]] [[world-gateway-2d-execution-state]].
