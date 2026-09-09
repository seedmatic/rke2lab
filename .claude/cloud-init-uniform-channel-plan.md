# Cloud-init uniform channel — migration plan

**Decision (2026-09-09, user):** ONE bootstrap/config-delivery channel = **cloud-init `user-data`** for BOTH
provisioning modes — standalone (seed-master grows the mgmt master, host Pulumi) AND in-cluster
(CAPI+CAPN+CAPRKE2 grow workload nodes). Replaces the mgmt master's current **devlxd** path (`user.rke2lab.*`
keys read by node-base oneshots). Rationale + the ruled-out alternatives (devlxd-uniform impossible — CAPN
hardcodes `cloud-init.user-data`; D1 asymmetry rejected for uniformity) live in memory
`flox-gate-secret-flow-devlxd-then-certmanager`.

Shape = **U-a (contained):** keep the node-base oneshots that read `/run/rke2lab/node.env` + sops material
and write `config.yaml.d`/`server/tls`; change only the SOURCE — cloud-init populates those files instead of
devlxd. Do NOT rewrite the whole chain.

## Feasibility verdicts (grounded, 2026-09-09 plan agent)

- **A — NixOS cloud-init reading incus user-data: needs a SHIM.** cloud-init's LXD datasource hardcodes
  `/dev/lxd/sock`, no incus support; incus guests expose only `/dev/incus/sock` (no compat symlink); incus
  NoCloud seeding is done by `incus-agent` = VMs only, node-base is a **container**. Fix = **A1 (recommended):**
  a oneshot `Before=cloud-init-local.service` that curls `/dev/incus/sock` `/1.0/config/cloud-init.user-data`
  (+ `/1.0/meta-data`) → writes `/var/lib/cloud/seed/nocloud/{user-data,meta-data}`; `datasource_list:[NoCloud]`.
  Fallback A2 = symlink `/dev/lxd/sock → /dev/incus/sock` + `datasource_list:[LXD]` (bets on wire-format match).
- **B — CAPRKE2 vs baked rke2: reconcilable.** CAPRKE2 has NO skip-install; even `airGapped:true` runs
  `sh /opt/install.sh`. Fix = bake an **inert `/opt/install.sh` (exit 0)** + `/opt/rke2-artifacts` (+ matching
  `sha256sum-<arch>.txt` if `airGappedChecksum` set) + set `airGapped:true`. Install becomes no-op; CAPRKE2 owns
  `/etc/rancher/rke2/config.yaml` (merges with our `config.yaml.d/*`); its `systemctl enable/start rke2-server`
  drives the nix unit. Role caveat: node-base bakes `role=server`; CAPRKE2 workers want `rke2-agent` — workers
  `replicas:0` today (workload unit ~L512), resolve before phase 2.C (per-node role in cloud-config, or a 2nd
  image variant).
- **C — two user-data shapes, one channel: works.** Both on `cloud-init.user-data`; baked oneshots
  (identity/dualstack/tls-san/node-labels) guarded on `/run/rke2lab/node.env` presence → drop-ins in mgmt,
  no-op in workload (CAPRKE2 owns cluster-cidr/tls-san/node-name via its config.yaml). Arbitrary cloud-config
  (CAPRKE2 write_files+runcmd) requires REAL cloud-init (not a hand-parser) — why A's shim is mandatory.

## devlxd touch-point map (→ becomes)

- `nixos/identity.nix` (L30 sock, L43-48 six `user.rke2lab.*` scalars, L51-58 node.env, L61 hostname) → drop the
  devlxd fetch; keep hostname-from-`node.env`; `After=cloud-init.service`, `Before=rke2-server.service`,
  `ConditionPathExists=/run/rke2lab/node.env`.
- `nixos/sops.nix` (`rke2lab-sops-fetch` L72-100: `sops-age-key`→`/run/rke2lab/sops-age.key` 0400,
  `cluster-ca-bundle`→`/run/rke2lab/cluster-ca-bundle.yaml` 0400) → cloud-config `write_files` same paths/perms;
  delete/guard the fetch; `sops-install-secrets` `After=cloud-init.service`.
- `nixos/bootstrap-manifests.nix` (`rke2lab-server-manifests` L19-44: `server-manifests`→
  `.../server/manifests/rke2lab-bootstrap.yaml`) → rides mgmt cloud-config `write_files`; delete/guard; mgmt-only
  (workload gets kube-vip via CAPRKE2 preRKE2Commands/files).
- `nixos/rke2.nix` — dualstack/tls-san/node-labels UNCHANGED (read node.env, no devlxd). Caveat: `services.rke2`
  (L11-15) auto-starts rke2-server → must gate `After=cloud-init.service` (R1).
- `host/pulumi/incus-ingress/.../InstanceGrow.java` (createInstance L259-288 poses `user.rke2lab.*` + folds
  `extraDevlxdConfig`) → render ONE `cloud-init.user-data` cloud-config (write_files node.env + sops material +
  server-manifests); keep `imageBuildChecksum`/`imageFingerprint` (host-side replaceOnChanges triggers, not
  guest-read). `ClusterSeedScenario` L638-661 hands the 3 revealed values (CA bundle / age key / server-manifests)
  to the renderer as a typed record instead of pre-prefixed keys.

## Ordered steps (each independently buildable/validatable)

1. **Node-base cloud-init datasource shim (A1). ✅ DONE + VALIDATED LIVE (2026-09-09).** `nixos/cloud-init.nix`
   (services.cloud-init.enable, network.enable=false, datasource_list:[NoCloud], the prestage oneshot
   `rke2lab-cloud-init-seed` that curls `/dev/incus/sock` `/1.0/config/cloud-init.user-data` + `/1.0/meta-data`
   → `/var/lib/cloud/seed/nocloud/`) wired into `nixos/default.nix`. **GOTCHA fixed:** the seed oneshot must be
   `wantedBy`+`before` **`cloud-init-local.service`** (a normal-deps service `WantedBy=multi-user.target` on
   NixOS) — NOT `cloud-init.target` (this setup never activates it → the unit stayed `inactive (dead)`, seed
   never written, cloud-init found no datasource). **Validated:** built the image, `incus image import` under a
   TEST alias `node-base-citest`, launched `citest` with a marker `cloud-init.user-data`; confirmed shim
   `active (exited)`, seed `{user-data,meta-data}` present, and `/run/ci-marker=ok` written by cloud-init's
   write_files → NoCloud datasource on an incus CONTAINER works. (Manual test: `incus launch ... -p <seed-profile>`
   for the root disk + privileged config; `cloud-init` binary isn't on `incus exec` PATH so use the marker as
   ground truth, not `cloud-init status`.) R2 de-risked.
2. **Source swap identity/sops/manifests (U-a). ✅ DONE + VALIDATED (2026-09-09).** `identity.nix` drops the
   devlxd fetch (hostname from node.env, After=cloud-init.service, ConditionPathExists=node.env); `sops.nix`
   deletes `rke2lab-sops-fetch`, sops-install-secrets After=cloud-init.service + gated on cluster-ca-bundle.yaml;
   `bootstrap-manifests.nix` DELETED (server-manifests ride cloud-init write_files); `rke2.nix` adds
   ConditionPathExists=node.env to dualstack/tls-san/node-labels. **Validated on citest** (mgmt cloud-config with
   write_files node.env): identity=active, hostname=bioskop-mgmt-citest, the 3 config.yaml.d drop-ins written,
   sops-install-secrets skipped (no bundle). Confirmed empirically: `write-files` ∈ `cloud_init_modules` =
   cloud-init.service. Test-fidelity: needs `security.privileged=true` + `raw.lxc=lxc.mount.auto = proc:rw …`
   (posed at INSTANCE level by InstanceGrow, not the profile) else the /proc/sys/kernel/hostname write is denied.
3. **rke2-server gating (R1) ✅ DONE** (`rke2.nix` `systemd.services.rke2-server.after=[cloud-init.service]`).
   Inert `/opt/install.sh` shim (B) — TODO at step 5 (CAPRKE2 airGapped, workload-only). **Validate:** workload
   cold-start — no premature cluster-init, baked binary used.
4. **InstanceGrow cloud-config generation (mgmt path). ✅ DONE + GROW-VALIDATED LIVE (2026-09-09, commit
   `5f21d32dd`).** `InstanceGrow` renders node.env + the revealed `NodeBootstrapMaterial` (sops age key, CA
   bundle, server-manifests) into a base64 `write_files` cloud-config posed as `cloud-init.user-data` (dropped
   all `user.rke2lab.{node-*,cluster-*}` keys + the extra map; kept imageBuildChecksum/imageFingerprint).
   `NodeBootstrapMaterial` = a host-only handoff record beside InstanceGrow (NOT the contract — moved there after
   the spec-coverage gate flagged it). `ClusterSeedScenario` builds it from the cellar reveals. **Validated on the
   real bioskop-mgmt grow (master renew):** cloud-init seed user-data=71988B, node.env real values, the 3 drop-ins,
   `rke2lab-identity` + `sops-install-secrets` BOTH active (CA delivered+decrypted+installed via cloud-init),
   server-manifests bootstrap present (Flux seeded), rke2 up, kube API available, cluster reconciling — the mgmt
   bootstrap flows entirely through cloud-init, no devlxd `user.rke2lab.*`. (Same grow validated the image GC+alias:
   one image, new fingerprint 773dcdd8, alias node-base moved.) **Steps 1-4 = the standalone/mgmt half SHIPPED.**
5. **Workload RKE2ConfigTemplate airGapped + inert install shim. ✅ DONE (2026-09-09, commit `d078cff3f`).**
   `RKE2ControlPlane.spec.airGapped=true` + `RKE2ConfigTemplate.spec.template.spec.airGapped=true`
   (ClusterApiWorkloadManifestsUnit); `nixos/capn-airgapped.nix` bakes an inert `/opt/install.sh` (exit 0) +
   empty `/opt/rke2-artifacts` so CAPRKE2's install no-ops onto the baked rke2 (nix eval + mvnw manifests-core
   BUILD SUCCESS). Only CAPRKE2 workload nodes invoke it; mgmt master never does. **Validates at the first
   workload greenfield** (needs the workload CA = C2). Role caveat: node-base bakes role=server; workers
   (replicas:0) need the server-vs-agent split before 2.C. **Steps 1-5 = the full cloud-init channel (mgmt
   shipped+grow-validated; workload code-complete, validates at first birth).**
6. **Cold-start end-to-end both modes.**

## Top risks

- **R1 (highest)** — baked rke2-server auto-start races CAPRKE2's late config → gate `After=cloud-init.service`.
- **R2** — incus datasource on NixOS → A1 NoCloud shim, validated standalone in step 1; A2 symlink fallback.
- **R3** — CAPRKE2 install vs baked rke2 → inert install.sh + airGapped; assert nix-store binary runs.
- **R4** — image is role=server only → workers replicas:0 today; resolve before phase 2.C.
- **R5** — cloud-init.user-data size/secrets (node.env + CA bundle + server-manifests in one key) → measure vs
  incus config-value limit; age key is the only plaintext (same surface as today's `user.rke2lab.sops-age-key`).

## Ties to workload-grow foundations

This unblocks the 2b secret-delivery layer (workload CA = C2: 4 CAPRKE2 tls secrets `<cluster>-{ca,cca,etcd,peer-etcd}`
minted by the cluster-pki seal, NODE_BOOTSTRAP into mgmt where CAPRKE2 reads them). The 2b render MECHANICS (loop
{mgmt}∪workloadTargets, fully-blind) stay delivery-agnostic and codeable independently. See
`.claude/workload-grow-foundations-plan.md`.
