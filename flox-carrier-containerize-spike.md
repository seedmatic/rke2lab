---
name: flox-carrier-containerize-spike
description: "Spike (flox 1.14, linux node) — how flox containerize provides the carrier /etc contract, and why NO real-file post-step is needed (plugin overlays /nix wholesale from host)"
metadata:
  type: reference
---

**Spike run 2026-08-27 on `bioskop-nixos` (linux aarch64), flox 1.14 — settled the carrier-contract OPEN question for the [[flox-envs-runtime-crd-delivery]] chantier.** Findings persisted in the `flox-controller` repo (`docs/design.adoc` § carrier-contract invariant — incl. the "`/nix` disposable / host authoritative" C4 figure — + § Verified; `config/samples/floxenv-carrier.yaml`).

**How `flox containerize` provides `/etc/{passwd,group,nsswitch.conf,ssl}`** — via nixpkgs **`fakeNss`**: in the image rootfs they are **`/nix/store/…-fake-nss` SYMLINKS, NOT real files**. Content is already correct (`passwd` = `root:x:0:0:…:/bin/sh` + `nobody`, so `getpwuid(0)` finds root). `flox containerize` has **no file-injection lever**: `[containerize.config]` (experimental) exposes only OCI config (`user`/`cmd`/`exposed-ports`/`volumes`/`working-dir`/`labels`/`stop-signal`); `user` adds a passwd entry but still through `fakeNss`. So flox can't emit real `/etc/*` natively — nor via `[build]` (yields a store path, not real files) nor a `[hook]` (runs INSIDE activation, AFTER flox's startup getpwuid → too late for that file).

**Why fakeNss symlinks are NOT a problem here (the key insight).** The NRI plugin ([`flox-nri-overlay-hook.sh`], invoked `overlay nix /nix /nix` at [`plugin.go:212`]) mounts an **overlayfs at `/nix`** with **lower = the HOST `/nix` (ro)** + an empty tmpfs upper — it **replaces the image's own `/nix` wholesale**. So the image's `/nix` is **DISPOSABLE; the host `/nix` is AUTHORITATIVE** for everything the carrier references — including its own **entrypoint**, a store path (`…-environment-dev/libexec/flox-activations`). Hence the fakeNss `/etc` symlinks are **no more fragile than the entrypoint**: they resolve **iff** their store paths sit on the HOST store — exactly what the whole carrier already needs to start at all.

**Consequence for the controller design (Route B, not real files):** a real-file post-step is **unnecessary** — its robustness is illusory (a real `/etc/passwd` saves nothing when the entrypoint store path is absent). The ONE requirement on the controller's `image` branch: **realise AND GC-root the carrier's FULL containerize closure** (superset of the activation closure: `environment-dev` + `fake-nss` + `passwd`/`group`/`nsswitch` + cacert + shell/coreutils) on **every node** running the carrier — else `nix.gc` reaps `fake-nss` and the symlink dangles. This is precisely the root cause of the earlier baked regression [[flox-carrier-nix]]: the baked model cooked fakeNss into the image but never realised it on the host, so the `/nix→host` overlay left it dangling. The controller model fixes the cause by realising the carrier ON the node.

**Also verified:** `flox containerize` needs **no FloxHub** — daemonless tar build from a LOCAL env succeeds with only a `not logged in` warning (exit 0). On macOS containerize requires a local runtime that can bind local paths (a remote docker/podman fails `statfs` on local paths); on linux it writes the tar via a pure nix derivation (no daemon) — the faithful path since targets are linux nodes.

Scratch env left at `/tmp/carrier-spike` on `bioskop-nixos` (harmless). See [[flox-envs-runtime-crd-delivery]] [[flox-carrier-nix]].
