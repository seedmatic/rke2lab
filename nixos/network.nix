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
# DNS + mDNS are NOT taken over here: this is an incus CONTAINER, so /etc/resolv.conf is provided by
# incus (networking.useHostResolvConf) and .local is answered by avahi (userspace — ./host-access.nix).
# systemd-resolved is deliberately DISABLED: enabling networkd turns it on by default as networkd's
# DNS backend, but it is unsupported alongside the host resolv.conf a container inherits
# (networking.useHostResolvConf, which asserts against resolved). That is ndh's VM-only path — here
# DNS stays incus's resolv.conf and .local stays avahi. networkd owns links/addresses/routes only.
{ lib, ... }:
{
  systemd.network.enable = true;
  services.resolved.enable = lib.mkForce false;
  # networkd owns the interfaces; keep the legacy per-interface DHCP scripting (dhcpcd) off.
  networking.useDHCP = false;

  # network-online.target waits for BOTH RequiredForOnline links below to be routable — bounded so a
  # degraded link cannot hang the boot forever (consumers depend on it via `wants`, a soft edge).
  systemd.network.wait-online = {
    enable = true;
    timeout = 60;
  };

  # lan0 — the canonical LAN bridge (same L2 as the operator's Mac): the node's EXTERNAL egress.
  # Preferred default route (lowest metric).
  systemd.network.networks."10-lan0" = {
    matchConfig.Name = "lan0";
    networkConfig.DHCP = "yes";
    dhcpV4Config.RouteMetric = 100;
    ipv6AcceptRAConfig.RouteMetric = 100;
    linkConfig.RequiredForOnline = "routable";
  };

  # vmnet0 — the per-cluster internal bridge (pod/cluster comms). Required for online (rke2 needs
  # it), backup default route (higher metric).
  systemd.network.networks."10-vmnet0" = {
    matchConfig.Name = "vmnet0";
    networkConfig.DHCP = "yes";
    dhcpV4Config.RouteMetric = 200;
    ipv6AcceptRAConfig.RouteMetric = 200;
    linkConfig.RequiredForOnline = "routable";
  };
}
