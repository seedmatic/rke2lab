---
name: gh-token-env-shadows-sso-keyring
description: "A GH_TOKEN in the session env shadows the SSO-authorized keyring token, so gh 403s on SAML orgs"
metadata:
  node_type: memory
  type: project
  originSessionId: ee54cec5-9d2e-4a28-a017-90430ec22251
  modified: 2026-09-27T13:19:43.599Z
---

Any `gh`/`git` call against a **SAML-enforced org** (`HylandSoftware`, `HylandExperience`,
`Hyland-Copilot`, `HylandPlatformConfiguration`, `HylandCloudOperations`, `Alfresco`,
`nuxeo`) fails from a Claude session with:

> `Resource protected by organization SAML enforcement. You must grant your OAuth token
> access to this organization.` (HTTP 403)

…while the **same command in the user's interactive shell succeeds**.

**Why:** the session environment carries a `GH_TOKEN` (a `gho_` OAuth token) that is *not*
SSO-authorized, and it **takes precedence over the keyring token** that `gh auth login`
refreshes. So re-authorizing in the browser, re-running `gh auth login`, or reloading the
VSCode window all appear to change nothing — the env var keeps winning. Org membership is
*not* the problem: `gh api user/orgs` lists the org fine.

**How to apply:** unset it per call rather than chasing the grant —

```bash
env -u GH_TOKEN -u GITHUB_TOKEN gh api repos/HylandSoftware/<repo>
env -u GH_TOKEN -u GITHUB_TOKEN git clone --bare https://github.com/HylandSoftware/<repo>.git
```

Discriminator in one command: the same call with and without `env -u GH_TOKEN`. If only the
stripped one works, it is this — not a missing grant, not a missing membership.

Corollary: installing a Claude Code **plugin from a private Hyland marketplace** (e.g.
`hyland-tools` → `HylandSoftware/hyland-ai-toolkit`) goes through the ambient git
credential, so it is exposed to the same shadowing. A marketplace add that fails is very
likely this rather than a bad manifest.

See [[swf-registry-workspace-and-toolchain]].
