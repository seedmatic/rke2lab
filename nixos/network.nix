# Node networking on systemd-networkd — aligned on ndh's pattern (per-link RequiredForOnline +
# RouteMetric), replacing the nixos-default dhcpcd.
#
# WHY: with dhcpcd the ONLY gate on network-online.target is dhcpcd.service (Type=forking, no wait
# flag), which backgrounds before acquiring ANY lease — so network-online is reached with no
# address/route, and every `After=network-online.target` unit (rke2-server, the rke2lab-rke2-config
# github fetch) runs against a dead network (proven live: target at boot+4s, lan0 lease + default
# route at boot+6s). systemd-networkd gates network-online on the links marked RequiredForOnline, so
# the target becomes truthful. (External WAN egress can still settle a few seconds past the lease, so
# the boot-time fetch keeps its own reachability retry — see ./rke2.nix rke2lab-rke2-config.)
#
# DNS: systemd-resolved manages it — the standard networkd companion. networkd hands resolved the
# per-link DNS from the lan0 DHCP lease and resolved serves it via its stub resolver (127.0.0.53).
# `networking.useHostResolvConf` MUST be OFF: the incus container default is ON, and that combination
# both asserts against resolved AND — once networkd took over the links — left /etc/resolv.conf with
# NO nameserver (DNS silently broke: `ping: Name or service not known`, networkd got the lease DNS but
# nothing wrote it to resolv.conf). Off → resolved owns /etc/resolv.conf and egress DNS works. mDNS
# `.local` stays with avahi (userspace — ./host-access.nix); resolved does no mDNS (off by default),
# so the two do not collide. networkd owns links/addresses/routes; resolved owns DNS.
{ ... }:
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

  # lan0 — the canonical LAN bridge (same L2 as the operator's Mac): the node's EXTERNAL egress and,
  # since vmnet0 takes no gateway (below), the SOLE default route. Low metric kept as an explicit
  # preference in case another egress link is ever added.
  systemd.network.networks."10-lan0" = {
    matchConfig.Name = "lan0";
    networkConfig.DHCP = "yes";
    dhcpV4Config.RouteMetric = 100;
    ipv6AcceptRAConfig.RouteMetric = 100;
    linkConfig.RequiredForOnline = "routable";
  };

  # vmnet0 — the per-cluster INTERNAL bridge: inter-node / pod-cluster comms ONLY, never an egress
  # path. Its incus-managed DHCP advertises 10.80.0.1 as a default gateway, but that gateway does NOT
  # route to the internet; installing it as a default route black-holes public traffic whenever it
  # out-prioritises lan0 (proven live: vmnet0's default metric 200 beat lan0's, so `ping www.free.fr`
  # left via 10.80.0.1 and died — the manual `ip route del default dev vmnet0` confirmed the fix).
  # UseGateway=false drops that default route (v4 + v6): the node keeps only the 10.80.0.0/21 link
  # route (automatic from the address) for inter-node reach, and lan0 stays the sole default. Still
  # required-for-online — rke2 needs the cluster link up.
  systemd.network.networks."10-vmnet0" = {
    matchConfig.Name = "vmnet0";
    networkConfig.DHCP = "yes";
    dhcpV4Config.UseGateway = false;
    ipv6AcceptRAConfig.UseGateway = false;
    linkConfig.RequiredForOnline = "routable";
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
  };
}
