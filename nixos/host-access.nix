# How the operator's world reaches a node: dbus-over-TCP for the seed-master systemd adapter, and
# mDNS advertisement so <cluster>-<node>.local resolves. Both are baked on EVERY node (the substrate
# is homogeneous), replacing the former rke2lab-dbus-tcp-system-bus.sh host script.
{ pkgs, ... }:
{
  # dbus-over-TCP for the seed-master systemd adapter. The adapter opens an anonymous-SASL DBus
  # connection to a node's system bus over TCP (port 12434) to read live unit state. FULLY
  # DECLARATIVE — no runtime node.env gate, no dbus restart. Two pieces:
  #   1. the anonymous-allow policy;
  #   2. a dbus.socket drop-in adding the TCP ListenStream beside the unix socket (the leading ""
  #      resets systemd's inherited list so the unix listener survives). dbus comes up with the TCP
  #      listener from boot.
  # Classic dbus-daemon, NOT dbus-broker (the NixOS default): dbus-java's anonymous SASL over TCP
  # relies on <auth>ANONYMOUS</auth> + <allow_anonymous/>, which the broker does not honour.
  services.dbus.implementation = "dbus";
  services.dbus.packages = [
    (pkgs.writeTextDir "share/dbus-1/system.d/40-rke2lab-allow-all.conf" ''
      <!DOCTYPE busconfig PUBLIC "-//freedesktop//DTD D-BUS Bus Configuration 1.0//EN"
       "http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd">
      <busconfig>
        <auth>ANONYMOUS</auth>
        <allow_anonymous/>
        <policy context="default">
          <allow send_type="method_call"/>
          <allow send_type="method_return"/>
          <allow send_type="signal"/>
          <allow send_type="error"/>
          <allow send_destination="*"/>
          <allow receive_type="method_call"/>
          <allow receive_type="method_return"/>
          <allow receive_type="signal"/>
          <allow receive_type="error"/>
          <allow eavesdrop="true"/>
          <allow own="*"/>
        </policy>
      </busconfig>
    '')
  ];
  systemd.sockets.dbus = {
    overrideStrategy = "asDropin";
    socketConfig.ListenStream = [
      ""
      "/run/dbus/system_bus_socket"
      "0.0.0.0:12434"
    ];
  };

  # NO mDNS. A node used to publish `<cluster>-<node>.local` over avahi because that was the only
  # named way to reach it: the container is not a tailnet member, so the operator and the seed's
  # systemd-adapter probe resolved it through mDNS on the shared home L2.
  #
  # Both halves of that are gone. The node's NIC moved to the FABRIC bridge, and mDNS is link-local —
  # `224.0.0.251` is not routed — so an advertisement there reaches only co-tenants of the same
  # bare-metal, never the operator, who now arrives over the tailnet. And the name itself moved: the
  # bare-metal's dnsmasq registers `<cluster>-<node>.<host>` from the node's own DHCP hostname
  # (`dns.mode=dynamic`), served to the tailnet by split-DNS. Every consumer was repointed —
  # the apiserver SANs, the operator kubeconfig's first-contact context, and the systemd probe
  # (SystemdAdapterScenario now reads `names().nodeFabricFqdn()`).
  #
  # So this is a removal, not a regression: address AND name are now served by ONE authority instead
  # of the bbox for DHCP plus avahi for names — which is the split that made an mDNS name necessary.
}
