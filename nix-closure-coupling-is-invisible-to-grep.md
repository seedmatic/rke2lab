---
name: nix-closure-coupling-is-invisible-to-grep
description: "A heavy build dependency can enter a nix system closure through a DERIVATION INTERPOLATION, with no `nix build` anywhere — so a textual audit of activation scripts clears it wrongly. Measure the closure (`nix path-info -r`), not the text"
metadata:
  node_type: memory
  type: reference
  originSessionId: 62e4b01e-08e6-453e-9c0f-6cfcc67c6820
  modified: 2026-10-03T07:05:11.107Z
---

Measured in **ndh** on 2026-10-03, while instructing "why does `darwin-rebuild switch` build the
`nerd-nixos` disk image?".

## ⛔ The instinctive audit returns EMPTY and is wrong

```bash
grep -n "nix build\|nix run\|nix-build\|nixos-generate" modules/darwin/tart-config.d/activation.sh
# -> nothing, in 1370 lines
```

A reader stopping there concludes there is no coupling. There is.

## ★ The real mechanism

```nix
# flake.nix:1819-1822
tart.configGenerator.rawImageStorePath = "${nixosDiskImageBringupSystemdZfs}/boot.img";
tart.configGenerator.runtimeSystemPath = nixosOutputs.runtimeSystem;  # system.build.toplevel
```

Interpolating a derivation into a string makes it a **build input**. The path lands in a file of the
closure (here via `pkgs.replaceVars` and `ln -s` inside a `runCommand`), so the toplevel **cannot be
realised** without realising the image first. The activation script never calls nix — it does not
need to.

★ And the dragged artefact is not only the image: `runtimeSystem` is a **whole NixOS
`system.build.toplevel`**, so a darwin activation builds a complete NixOS system as a side effect.

## ✅ The right measurement

```bash
nix path-info -r <darwin-toplevel> | grep -c .     # how many paths the closure really holds
nix path-info -S <bundle>                          # its total size
```

An **eval that succeeds proves nothing** about weight — weight is a property of the closure, so read
the closure.

★ Corollary worth keeping: the cost may already be written down by the module's own author. Here
`modules/darwin/tart-config.nix:112-135` states that dropping one flag saves "~16 GiB and ~1800
store paths" and another pulls "~15 GiB of raw bytes". Read the comments before estimating — an
invented estimate next to an authored one is strictly worse.

Same family as [[measure-the-derived-value-not-the-assumed-one]] (ask what the value is *about*) and
the grep faults in [[build-barrier-covers-a-tree-not-a-sha]]: a search that *can* answer "nothing"
must never be read as "there is nothing".
