---
name: go-sigkill-is-in-place-signing
description: "On this seat `go test`/`go run` die with a bare `signal: killed` — the Go linker applies its ad-hoc signature IN PLACE and this $TMPDIR (a tmpfs) does not present it to the kernel, so AMFI kills the process. NOT a noexec mount. Fix: GOTMPDIR on a normal filesystem"
metadata:
  node_type: memory
  type: reference
  originSessionId: 62e4b01e-08e6-453e-9c0f-6cfcc67c6820
  modified: 2026-10-03T06:32:32.271Z
---

Measured on 2026-10-02/03, darwin arm64, `$TMPDIR=/tmp/.nxmatic` (a **tmpfs**).

**The symptom is maximally misleading:** `go run .` or `go test ./...` print a bare

```text
signal: killed
```

with **no other output** — indistinguishable from a crash of the code under test. Even a
hello-world dies. It cost two sessions real time, and the first explanation offered (and believed)
was wrong.

## ⛔ It is NOT a `noexec` mount

Two reasons, both measured:

* a `noexec` mount refuses with a **permission** error, it does not deliver a **SIGKILL**;
* and this filesystem is not `noexec` at all — a shell script written there and executed runs fine.

## ★ The real mechanism, pinned by three cases

```text
linked DIRECTLY on $TMPDIR, run there   -> exit 137 (SIGKILL)
that SAME file copied off $TMPDIR       -> runs
linked elsewhere, then copied INTO it   -> runs
```

So it is neither the filesystem (executing from it is fine) nor the binary (the same bytes run
elsewhere). It is the **combination**: the Go linker applies its ad-hoc signature **in place**, and
on this filesystem the signature state the kernel sees at `exec` does not match, so AMFI kills the
process. A `cp` writes a fresh file in one go, hence the copy's immunity. `codesign -dv` reports the
binary as `adhoc, linker-signed` — the signature is **there**; its validation is what fails.

★ That also explains the detail that misled us: `go test -c -o <path>` links **outside** `$TMPDIR`
and passes, while `go test` links **inside** and execs immediately, so it always dies. Same binary,
two link locations.

## ✅ The fix

```bash
GOTMPDIR=/private/tmp/gotmp go test ./...   # ok
go test ./internal/...                      # FAILS — keep this control
```

Export `GOTMPDIR` to a normal filesystem (a devShell can do it). Fallback if `GOTMPDIR` is not
honoured somewhere: `go test -c -o ./x.test && ./x.test`.

⚠️ **Anywhere Go is touched on this seat.** `seed-incluster` is Go and its `buildGoModule` runs
tests in the check phase: inside the nix sandbox it passes, but a hand-run `go test` on this seat
will die.

★ And measure without a pipe: a first diagnosis was invalid because it read the exit status of a
`head`. Cf. [[measure-the-derived-value-not-the-assumed-one]].
