---
name: migration-branch-no-fallback
description: "User principle for the OSGi-migration branches (stated 2026-06-20, feature/osgi-runtime-r4-boot-seam): carry NO fallback logic. Once a thing is useless after the target is reached, throw it away the moment it gets in the way — don't repair dead code just to delete it later. Maintain ONLY what is part of the migration."
metadata:
  node_type: memory
  type: feedback
---

User, verbatim (2026-06-20): "je confirme, on jette tout ce qui ne sert a rien une fois notre
target atteinte, des que ca pose probleme. dans cette branche, on ne traine pas de la logique
fallback. on maintient ce qui fait partie de la migration seulement."

**Why:** in a migration branch the end state is known. Keeping fallback paths or repairing
tombstoned code "to be safe" adds surface that the target deletes anyway — it's pure drag, and it
muddies review (you can't tell migration code from soon-to-die code).

**How to apply:**
- When a refactor breaks a caller that is already `@Deprecated(forRemoval=true)` / tombstoned,
  DELETE it now rather than fixing its call sites — don't do throwaway work on dead code. (Applied:
  the realgraph fixture, 8 self-contained files, deleted the moment the `UnitResolver(universe,
  Resolver)` signature broke them, instead of waiting for Milestone C.)
- The R4 dual-path (`osgiRuntime != null ? awaitService : ServiceLoader`) survives ONLY because it
  IS part of the migration (retires in R5) — not as a comfort fallback. See
  [[dual-path-inline-until-r5]].
- Don't add "just in case" branches, compatibility shims, or keep-the-old-path flags. If the target
  removes it, it has no place in the branch that builds the target.

See [[osgi-runtime-r4-resume-state]] [[r4-resolver-service-ification]].
