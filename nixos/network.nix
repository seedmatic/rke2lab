# Node networking on systemd-networkd — aligned on ndh's pattern (per-link RequiredForOnline +
# RouteMetric), replacing the nixos-default dhcpcd.
#
# WHY: with dhcpcd the ONLY gate on network-online.target is dhcpcd.service (Type=forking, no wait
# flag), which backgrounds before acquiring ANY lease — so network-online is reached with no
# address/route, and every `After=network-online.target` unit (rke2-server, the rke2lab-rke2-config
# github fetch) runs against a dead network (proven live: target at boot+4s, fabric0 lease + default
# route at boot+6s). systemd-networkd gates network-online on the links marked RequiredForOnline, so
# the target becomes truthful. (External WAN egress can still settle a few seconds past the lease, so
# the boot-time fetch keeps its own reachability retry — see ./rke2.nix rke2lab-rke2-config.)
#
# DNS: systemd-resolved manages it — the standard networkd companion. networkd hands resolved the
# per-link DNS from the fabric0 DHCP lease and resolved serves it via its stub resolver (127.0.0.53).
# `networking.useHostResolvConf` MUST be OFF: the incus container default is ON, and that combination
# both asserts against resolved AND — once networkd took over the links — left /etc/resolv.conf with
# NO nameserver (DNS silently broke: `ping: Name or service not known`, networkd got the lease DNS but
# nothing wrote it to resolv.conf). Off → resolved owns /etc/resolv.conf and egress DNS works. mDNS
# There is no mDNS at all any more (see ./host-access.nix — the node's name is served by its
# bare-metal's dnsmasq, not advertised link-locally), and resolved does none either (off by default).
# networkd owns links/addresses/routes; resolved owns DNS.
{ netplan, ... }:
{
  systemd.network.enable = true;
  services.resolved.enable = true;
  # incus containers default useHostResolvConf ON — asserts against resolved AND yields an empty
  # resolv.conf under networkd. Off so resolved owns DNS (fed by the DHCP lease via networkd).
  networking.useHostResolvConf = false;
  # networkd owns the interfaces; keep the legacy per-interface DHCP scripting (dhcpcd) off.
  networking.useDHCP = false;

  # network-online.target waits for BOTH RequiredForOnline links below to be routable — bounded so a
  # degraded link cannot hang the boot forever (consumers depend on it via `wants`, a soft edge).
  systemd.network.wait-online = {
    enable = true;
    timeout = 60;
  };

  # fabric0 — the canonical FABRIC bridge, ndh's per-bare-metal segment. NOT the home LAN and NOT the
  # operator's L2 any more, which is what the old name claimed: the node is reached across it through
  # the tailnet route its Incus host advertises, and its public egress is masqueraded by that host
  # (ndh's baremetal-nat, scoped to the whole /20 slice). It is still the node's SOLE default route,
  # since vmnet0 takes no gateway (below) — the gateway is the bridge, served by the segment's
  # dnsmasq, which also hands out the per-link DNS. Low metric kept as an explicit preference in case
  # another egress link is ever added.
  #
  # ⚠️ The segment is declared `ipv6.address = none`, so this link is v4-ONLY — unlike the home LAN it
  # replaced, which carried a v6 beside its v4. Anything needing a dual-stack address must take it
  # from vmnet0 (see rke2lab-node-ip in ./rke2.nix, where exactly that assumption broke).
  systemd.network.networks."10-fabric0" = {
    matchConfig.Name = "fabric0";
    networkConfig.DHCP = "yes";
    dhcpV4Config.RouteMetric = 100;
    ipv6AcceptRAConfig.RouteMetric = 100;
    linkConfig.RequiredForOnline = "routable";
  };

  # vmnet0 — the per-cluster INTERNAL bridge: inter-node / pod-cluster comms ONLY, never an egress
  # path. Its incus-managed DHCP advertises 10.80.0.1 as a default gateway, but that gateway does NOT
  # route to the internet; installing it as a default route black-holes public traffic whenever it
  # out-prioritises fabric0 (proven live: vmnet0's default metric 200 beat fabric0's, so `ping www.free.fr`
  # left via 10.80.0.1 and died — the manual `ip route del default dev vmnet0` confirmed the fix).
  # UseGateway=false drops that default route (v4 + v6): the node keeps the /21 link route (automatic
  # from the address) for inter-node reach plus the explicit supernet route below for its SIBLING
  # clusters, and fabric0 stays the sole default. Still required-for-online — rke2 needs the cluster link
  # up.
  systemd.network.networks."10-vmnet0" = {
    matchConfig.Name = "vmnet0";
    networkConfig.DHCP = "yes";
    dhcpV4Config.UseGateway = false;
    ipv6AcceptRAConfig.UseGateway = false;
    linkConfig.RequiredForOnline = "routable";
    # BOTH families, and this is what makes `network-online.target` mean what rke2 needs. "routable"
    # alone is satisfied by the v4 lease, so the target could be reached while the stateful DHCPv6
    # lease was still outstanding — and rke2lab-node-ip, ordered after it, then read a half address.
    # cluster-cidr is dual-stack unconditionally, so rke2 refuses a single-family node-ip outright
    # (measured 2026-09-24: `node-ip: [10.80.8.5]` against `[10.45.0.0/16 fd00:45::/56]`).
    #
    # Declaring the wait HERE rather than polling in the oneshot is the difference between a
    # guarantee and a hope: the ordering already exists, it was only under-specified. And when the
    # lease genuinely never arrives, the failure is networkd's own wait-online timeout naming this
    # link — not a drop-in quietly written with whatever was available.
    linkConfig.RequiredFamilyForOnline = "both";
    # Force a link-layer DUID (DUID-LL = the MAC, no vendor/timestamp) for the stateful DHCPv6
    # client. WHY: the vmnet bridge's dnsmasq pins each node's deterministic address by a
    # MAC-keyed reservation (`dhcp-host=52:54:00:00:00:00,10.80.0.10,[fd96:…::a50:a],…`). DHCPv6
    # has no chaddr, so dnsmasq can only recover the client MAC by extracting it from a link-layer
    # DUID; networkd's DEFAULT DUID-EN (systemd enterprise 43793) carries no MAC, so the v6
    # reservation never matched and the node leased a DYNAMIC address instead of `::a50:a`. The
    # kubelet then rejected the (unresolvable) v6 half of its dual-stack --node-ip and set NO
    # node addresses at all — breaking `kubectl logs`/`exec` cluster-wide. DUID-LL restores the
    # match, so the node gets its deterministic v6 and --node-ip validates. (v4 is unaffected:
    # dnsmasq matches the MAC from the DHCPv4 chaddr directly.)
    dhcpV6Config.DUIDType = "link-layer";

    # How a cluster reaches its SIBLINGS on the same host. Each cluster owns a /21 of the vmnet
    # supernet on its own bridge, and the two never met: a node held only its own /21 link route, so
    # another cluster's VIP fell through to the fabric0 default and out to the home router, where it
    # died. CAPN states the requirement plainly — "the management cluster can connect to the VIP
    # address" — because `Cluster.spec.controlPlaneEndpoint` IS that VIP, so CAPI and CAPRKE2 reach a
    # managed cluster through it.
    #
    # Nothing is needed on the HOST: it already holds both bridges with their gateways
    # (vmnet-mgmt 10.80.0.1/21, vmnet-wrkld 10.80.8.1/21) and `net.ipv4.ip_forward = 1`, so it
    # forwards between them today. Only the node lacked the route. That also keeps this OFF the
    # tailnet: the clusters are co-located, so the hop is local.
    #
    # `_dhcp4` rather than a literal gateway, because this image is FLEET-WIDE and does not know
    # which cluster it will boot into: networkd substitutes the gateway from THIS node's own lease,
    # which is its own vmnet gateway. It is the same gateway UseGateway=false rejects above — refused
    # as a DEFAULT route (it reaches no internet), accepted for the supernet, which is all it serves.
    # The /21 link route stays preferred for the node's own network (longest prefix); this catches
    # every sibling, present and future, with no per-cluster variant of the image.
    routes = [
      {
        Destination = netplan.vmnetSupernet;
        Gateway = "_dhcp4";
      }
    ];
  };
}
