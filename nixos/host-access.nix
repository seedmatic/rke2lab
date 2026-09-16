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

  # mDNS advertisement — the seed-master systemd adapter (and the incus remote) reach this node by
  # its <cluster>-<node>.local name, so the node must ANSWER that name over mDNS. avahi (userspace)
  # does this — kept over systemd-resolved because this is an incus CONTAINER inheriting incus's
  # /etc/resolv.conf, which resolved does not support (see ./network.nix). rke2lab-identity sets the
  # hostname at runtime (ordered before avahi), and avahi publishes it + its LAN addresses.
  services.avahi = {
    enable = true;
    ipv4 = true;
    ipv6 = true;
    # Advertise ONLY on lan0 (the canonical LAN bridge, same L2 as the operator's Mac). The node
    # also carries vmnet0 (the internal per-cluster bridge, e.g. 10.80.8.0/21) whose address is NOT
    # routable from outside the cluster; advertising there would let a resolver pick the dead IP for
    # <cluster>-<node>.local and the systemd-adapter probe would connect to nothing. lan0 is the
    # instance NIC name InstanceGrow assigns, stable across boots.
    allowInterfaces = [ "lan0" ];
    publish = {
      enable = true;
      addresses = true;
      workstation = true;
    };
  };
}
