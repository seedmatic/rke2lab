<!-- @codebase -->
# rke2lab-netplan

Canonical network addressing derivation and integration contracts for rke2lab cluster networking.

## Module ownership and integration points

- `netplan` owns canonical addressing/value-model logic (`ClusterNetworkBlueprint`, `Cidr`,
	`MacAddress`).
- `netplan.api` owns request/result and SPI contracts (`NetplanSynthesisService`,
	`NetplanSynthesisRequest`, `NetplanSynthesisResult`, `Net2PlanEndpoint`).
- `controlplane` consumes `netplan` as a dependency and should not duplicate network model
	classes.
- `controlplane` may contribute provider/runtime orchestration, but network derivation semantics
	remain centralized in `netplan`.

## Nix integration note (`nix-darwin-home`)

`rke2lab-netplan` is now the canonical source of truth for cluster underlay addressing that is
mirrored into the `nix-darwin-home` network catalog (`catalog.netplan.rke2lab`).

Integration intent:

- Keep one canonical network model in `rke2lab-netplan` (supernet, per-cluster slice CIDRs,
	gateway conventions, and stable cluster indexing).
- Consume those values in `nix-darwin-home` host modules for static routes
	and D-Bus TCP bind addresses.
- Avoid hand-maintained duplicate CIDRs in host-specific Nix code paths; derive from the shared
	catalog shape instead.

When netplan blueprint semantics change, update this module first and then propagate catalog
consumers in `nix-darwin-home` so both repos remain contract-aligned.

## Net2Plan endpoint contract (future integration)

The netplan API already accepts an optional Net2Plan endpoint contract so mesh/topology planner integration can be added without changing request contracts.

Configure via JVM properties:

- `rke2lab.netplan.net2plan.endpoint` (example: `https://net2plan.example.internal:8443`)
- `rke2lab.netplan.net2plan.path` (optional, default: `/api/network-plans`)

Or via environment variable:

- `RK2LAB_NET2PLAN_API_ENDPOINT`

When configured, CLI synthesis logs the canonical network-plan URL resolved from the endpoint + path.
