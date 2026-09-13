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
