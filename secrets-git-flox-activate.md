---
name: secrets-git-flox-activate
description: "ANY git op touching .secrets MUST run under `flox activate`, and verify the committed blob is ENC[...] before pushing — a plaintext leak happened on 2026-08-30."
metadata: 
  node_type: memory
  type: feedback
  originSessionId: f37aea8f-e85f-4a18-9278-ff26bcb03565
  modified: 2026-08-30T10:13:21.876Z
---

**`.secrets` is a sops-clean/smudge-filtered tracked file: the working copy is DECRYPTED, the committed blob must be ENCRYPTED. Two ways it silently leaks plaintext — both bit on 2026-08-30 (I committed + pushed all of `.secrets` in cleartext to `seedmatic/rke2lab`).**

**Why:** the sops `clean` filter (git config `filter.sops-yaml.clean = ~/.config/git/sops.d/yaml-clean`) needs `sops` + the age key on PATH/env. Run `git add .secrets` **outside** `flox activate` → the filter can't encrypt → it **passes the plaintext through** (dangerous fail-open), and the plaintext commits + pushes. SEPARATELY, sops only encrypts a value whose preceding comment matches `encrypted_comment_regex: sops:encrypted` — `# sops: encrypted` (with a SPACE) does NOT match → that field commits plaintext even under the filter.

**How to apply:**
- Run EVERY git op that stages/commits `.secrets` (or any sops-filtered file) under **`flox activate -- git …`** (add/commit/amend). Never bare `git add .secrets`.
- The seal marker comment is exactly `# sops:encrypted` (NO space). Grep new `.secrets` fields for a mismatched `# sops: encrypted`.
- **Before pushing**, VERIFY the committed blob is encrypted: `flox activate -- git show HEAD:.secrets | grep -c 'ENC\['` and grep for known plaintext prefixes (`gho_`, `flox_pat_`, the value) → must be 0. Only then push.
- Recovery when it leaks: fix (comment + re-add under flox activate), `git commit --amend`, `git push --force-with-lease`. But force-push does NOT erase the orphaned commit objects from GitHub (reachable by SHA until GC) + GitHub secret-scans pushes → **treat every exposed secret as COMPROMISED and ROTATE it**.

**2026-08-30 leak — ACTUAL blast radius (verified, NOT "everything"): ONLY `github.web-hook.token`.** The sops clean filter DID run (sops was on the shell PATH even without explicit `flox activate`) and encrypted every field correctly EXCEPT the web-hook token, which stayed plaintext SOLELY because of the `# sops: encrypted` comment typo (space) not matching the `sops:encrypted` regex. Verified on the orphaned commit `edd09d0ed`: 10 `ENC[` entries, 0 real-secret plaintext (no `gho_`/`flox_pat_`/RSA/EC/docker-pw), 1 web-hook token plaintext. **The GitHub App key, both PATs, Docker Hub, FloxHub, Cachix, incus key were all ENCRYPTED — intact, no rotation needed.** Impact of the leaked web-hook token: LOW (an HMAC key for a Flux Receiver that is not even deployed). Action: regenerate just that token. **CAUTION — my first response WRONGLY claimed "all secrets plaintext" by grepping only the web-hook token then ASSUMING the rest; verify EVERY field before declaring a blast radius.** Remote tip re-encrypted at `a552c3ba5` (public repo `seedmatic/rke2lab`); orphaned commits `edd09d0ed`/`60990ac97` still serve the web-hook token by SHA (low value, will be rotated).
