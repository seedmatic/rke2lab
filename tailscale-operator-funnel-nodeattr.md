---
name: tailscale-operator-funnel-nodeattr
description: "★ Tailscale-operator Ingress funnel needs ACL nodeAttrs {target: tag:k8s, attr: funnel} — autogroup:members EXCLUDES tagged devices; symptom = webhook 502 + public NXDOMAIN despite proxy 'Funnel on' locally."
metadata:
  type: reference
---

**★ A Tailscale-operator-managed `Ingress` funnel (`tailscale.com/funnel: "true"`) is served publicly ONLY if the tailnet ACL grants the `funnel` node attribute to the PROXY's tag — and the operator tags all its proxies `tag:k8s`, which `autogroup:members` does NOT cover (a TAGGED device has no user owner → it is not a member).** This bit us: the ACL had `nodeAttrs: [{target: ["autogroup:members"], attr: ["funnel"]}]` → the `ts-flux-webhook` proxy (`tag:k8s`) never got `funnel` → GitHub webhook delivery `502 failed to connect to host`, public DNS `NXDOMAIN`.

**The trap — it looks configured but isn't published:** the proxy applies the funnel serve config and even OBTAINS the Funnel TLS cert (cert issuance only needs the tailnet's *HTTPS Certificates* enabled, independent of the `funnel` attr), so `tailscale serve status` inside the proxy shows `# Funnel on: - https://<host>` and health is clean. But without the `funnel` attr the CONTROL PLANE never publishes the public ingress → **no public A/AAAA record** → the endpoint is unreachable from the internet. Local "Funnel on" ≠ publicly served.

**The fix (Tailscale admin ACL, NOT rke2lab code):**
```jsonc
"nodeAttrs": [ { "target": ["tag:k8s"], "attr": ["funnel"] } ]
```
(+ Funnel enabled for the tailnet in Settings; HTTPS Certificates already on if a cert was issued). Covers ALL operator proxies at once (flux-webhook, pac-webhook, headscale, …). Public DNS publishes within ~30s.

**Diagnosis recipe (verified 2026-09-01, tailnet `mammoth-skate.ts.net`, cluster `bioskop-mgmt`):**
- `gh api repos/<org>/<repo>/hooks/<id>/deliveries` → the ping/push status_code (`502 failed to connect` = endpoint unreachable, distinct from a 4xx app response).
- `dig @1.1.1.1 <host>.<tailnet>.ts.net A` → `NXDOMAIN` = funnel not published publicly (a public resolver, like GitHub's; the local resolver may negative-cache — use `curl --resolve host:443:<funnel-ip>` to bypass once DNS is live).
- Tailscale **operator lives in `mesh-system`** (`deploy/operator`); per-Ingress proxies are pods `ts-<ingress>-<hash>-0` there. `kubectl exec ts-<x>-0 -n mesh-system -- tailscale serve status` / `tailscale status --json` (→ `Self.Tags` = `["tag:k8s"]`, `CertDomains`, health).
- Reachable-but-app-level responses (404 on `/`, 400 on a GET to a webhook path) = funnel OK, the backend is answering.

**Topology note (not a bug):** with the Tailscale k8s operator the cluster NODES do NOT join the SaaS tailnet. Presence = the operator's devices (all `tag:k8s`): a `Connector`/subnet-router (`bioskop-mgmt-controlplane`, `advertiseRoutes: 10.80.7.10/32, 10.80.0.64/26`) makes cluster CIDRs reachable over the tailnet by ROUTE, plus per-Ingress funnel proxies. A proxy's `tailscale status` shows few peers (ACL-restricted visibility) — the full device list is the admin console. The workload mesh is separately **headscale** (self-hosted, in-cluster). See [[manifests-publish-in-cluster-render]] [[flux-per-service-kustomizations]].
