---
name: incus-master-renew-delete-without-recreate
description: "On a `pulumi up` that RENEWS/replaces the master incus instance, Pulumi DELETES it but does not re-create it in the same up (needs a second up). Root cause = deleteBeforeReplace(true) + a replacement create gated on a fallible Image upload. DIAGNOSED (2026-09-05), NOT fixed."
metadata: 
  node_type: memory
  type: project
  originSessionId: f37aea8f-e85f-4a18-9278-ff26bcb03565
  modified: 2026-09-05T17:20:24.972Z
---

**DIAGNOSED 2026-09-05 (agent), NOT fixed — a distinct chantier from the funnel work.** Symptom (user): on a re-grow that replaces the master, `pulumi up` deletes the master incus instance but does NOT re-create it in the same run; a SECOND `up` creates it.

**Where:** `host/pulumi/incus-ingress/src/main/java/io/seedmatic/rke2lab/controlplane/incus/InstanceGrow.java`, `createInstance(...)`, resource `com.pulumi.incus.Instance` logical name `"seed-instance"` (lines 316-347). Invoked once per run from `exec/seed-master/.../bdd/ClusterSeedScenario.java:657`.

**Lifecycle options (the cause):**
- `.deleteBeforeReplace(true)` (line 330) → a replace is **destroy-then-create**, NON-atomic.
- `.replaceOnChanges(["config","config.*"])` (line 331) → ANY `config` change arms a replace. The config folds volatile keys: `user.rke2lab.imageBuildChecksum` (line 283), `user.rke2lab.imageFingerprint` (lines 308-314), per-node PKI/manifests (`extraDevlxdConfig`, 289-300) → a node-base rebuild OR a rotated CA/age/manifests bundle triggers a replace.
- `.ignoreChanges(["image","devices"])` (line 346). No `protect`, no retry.

**Why the create half is skipped:** on a node-base **content rebuild** the new image fingerprint is not yet on the daemon → `imageExists(fingerprint)` false (`InstanceGrow.java:212`, `IncusImportLookup.imageExists:119-134`) → the grow declares a fresh `Image` upload resource (`seed-image-<fp>`, 219-235) and the instance create DEPENDS on that upload. With `deleteBeforeReplace(true)` the old master is destroyed FIRST; if the image upload/create then errors (the code flags a host-vs-daemon split-image fingerprint mismatch + duplicate-upload rejection as the likely vector, `splitImageFingerprint` 244-261, caveats 202-214), the create never commits → the master stays DELETED. The 2nd `up` differs: the image is now present → **adopt-by-omission** (212-214, no Image resource declared, create references `Output.of(fingerprint)`), and there is no delete pending → the create runs cleanly.

**Secondary (does NOT cause it):** `pruneStaleTailnetDevicesOnReplace` (377-397) is a `command:local:Command` ordered `dependsOn(instance)` (line 396) → it can only prune AFTER the new node boots, never before, which is why the operator prunes tailnet devices MANUALLY before re-grow. Guest-side, not the incus create.

**Fix direction (not applied):** avoid the non-atomic destroy-then-create for the master — either drop `deleteBeforeReplace` for create-before-delete where the incus daemon allows a transient name, OR make the image fully present/adopted BEFORE the instance replace is scheduled so the create is never gated behind a fallible post-delete upload.

See [[cold-start-cleanup-and-funnel-cert-persistence]] [[master-provisioning-state]].
