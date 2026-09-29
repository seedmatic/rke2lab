# OSGi runtime migration — SHIPPED slices — section index (rke2lab memory)

Section of the rke2lab memory index. Loaded on demand; the injected root is [MEMORY.md](MEMORY.md).
One line per entry (~200 chars); detail lives in the linked file.

## OSGi runtime migration — SHIPPED slices (durable record; how lives in git)

- [OSGi runtime migration state](osgi-runtime-migration-state.md) — design phase shipped; spec `wip/specs/2026-06-18-osgi-runtime-migration-design.adoc` (R1–R7 slices, monotone atlas). Impl slices schedulable.
- [R1–R3 SHIPPED — SCR stood up + SPIs declared + intra-bundle @Reference](osgi-runtime-r1-scr-state.md) — felix.scr 2.2.18 + DS API in BOM (R1, anti-cheat on embedded Felix); 10 impls `@Component` (R2); `NodeEnvContributorRegistry` binds 6 contributors via SCR (R3); testkit→`osgi/testkit`. Detail: [[osgi-runtime-r2-declare-spis-state]] [[osgi-runtime-r3-consume-references-state]] [[bnd-annotations-spike-state]].
- [R4 WI-C0 — service-ify the Felix Resolver](r4-resolver-service-ification.md) — **★ DECISION + DONE.** prod consumes the INJECTED `org.osgi.service.resolver.Resolver`; `new ResolverImpl` left to tests (test owns its dep). unitrepo-core imports only the service interface.
- [CLI OSGi migration](cli-osgi-migration-carto.md) — SHIPPED. manifests-cli + netplan-cli boot embedded Felix, read their `-port` from the registry; `netplan.api`→netplan-port (single-exporter); 8 orphaned META-INF/services deleted. See [[osgi-system-export-resolution-only]] [[dual-path-inline-until-r5]].
- [R4 resume-state (SHIPPED pointer)](osgi-runtime-r4-resume-state.md) — R4 shipped 2026-06-20; pointer + live backlog dominoes (CLIs, null-args, ThreadLocal readers, manifests-core scope, worktree provisioning).

