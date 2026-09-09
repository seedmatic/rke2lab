# sops-nix on the homogeneous substrate — the DECLARATION is baked, the per-cluster DATA arrives at
# runtime. The image never carries a PKI: sops.secrets names the cluster-CA files and their target
# paths under the rke2 server tls dir (build-time, identical on every node), while the encrypted
# bundle and the age identity are delivered over devlxd at boot (rke2lab-sops-fetch, below) into /run.
# validateSopsFiles=false lets defaultSopsFile be that runtime path; sops-install-secrets decrypts on
# the node and lays the CA set down before rke2-server, which then issues every leaf from it.
# See docs/architecture/cluster-api/deterministic-cluster-access.adoc.
{ ... }:
let
  tls = "/var/lib/rancher/rke2/server/tls";
  # bundle YAML key -> path under the rke2 server tls dir. The bundle is flat (etcd leaf CAs carry an
  # etcd- prefix); rke2 wants them under an etcd/ subdir, so the path re-nests. sops-install-secrets
  # splits `key` on '/', so the flat prefixed names stay single-level lookups.
  caFile = key: path: {
    inherit key path;
    owner = "root";
    group = "root";
    mode = "0600";
  };
in
{
  sops = {
    # The age identity and the bundle are delivered at runtime (devlxd) — never baked, never derived
    # from an ssh host key. Disable both auto-derivation paths.
    age.keyFile = "/run/rke2lab/sops-age.key";
    age.sshKeyPaths = [ ];
    gnupg.sshKeyPaths = [ ];

    # The bundle does not exist at build (it is a runtime path): skip the build-time store/existence
    # check, and run sops-install-secrets as a systemd service rather than an early activation script
    # (which would precede the devlxd delivery).
    validateSopsFiles = false;
    useSystemdActivation = true;
    defaultSopsFile = "/run/rke2lab/cluster-ca-bundle.yaml";

    # The bring-your-own-CA set rke2 finds under server/tls before first boot: five leaf CAs
    # (each crt+key) plus the service-account issuer key. Placed here, rke2 skips CA generation and
    # issues every leaf (apiserver, kubelet, etcd, SA) from these — rooting the cluster on our CA.
    secrets = {
      "server-ca.crt" = caFile "server-ca.crt" "${tls}/server-ca.crt";
      "server-ca.key" = caFile "server-ca.key" "${tls}/server-ca.key";
      "client-ca.crt" = caFile "client-ca.crt" "${tls}/client-ca.crt";
      "client-ca.key" = caFile "client-ca.key" "${tls}/client-ca.key";
      "request-header-ca.crt" = caFile "request-header-ca.crt" "${tls}/request-header-ca.crt";
      "request-header-ca.key" = caFile "request-header-ca.key" "${tls}/request-header-ca.key";
      "etcd-peer-ca.crt" = caFile "etcd-peer-ca.crt" "${tls}/etcd/peer-ca.crt";
      "etcd-peer-ca.key" = caFile "etcd-peer-ca.key" "${tls}/etcd/peer-ca.key";
      "etcd-server-ca.crt" = caFile "etcd-server-ca.crt" "${tls}/etcd/server-ca.crt";
      "etcd-server-ca.key" = caFile "etcd-server-ca.key" "${tls}/etcd/server-ca.key";
      "service.key" = caFile "service.key" "${tls}/service.key";
    };
  };

  # sops-install-secrets writes each secret to its target path; ensure the rke2 tls dirs exist (it
  # MkdirAll's the parent, but the etcd/ subdir + strict perms are declared here so the layout is
  # explicit and owned before first boot).
  systemd.tmpfiles.rules = [
    "d /var/lib/rancher/rke2/server/tls 0700 root root - -"
    "d /var/lib/rancher/rke2/server/tls/etcd 0700 root root - -"
  ];

  # The age identity + cluster-CA bundle now arrive through the UNIFORM cloud-init channel — the mgmt
  # cloud-config `write_files` /run/rke2lab/{sops-age.key,cluster-ca-bundle.yaml} (see ./cloud-init.nix;
  # host GROW renders it). write_files runs in cloud-init.service, so sops-install-secrets waits on it,
  # then runs before rke2-server. The tie to rke2-server is WEAK (wantedBy, not requiredBy) and the
  # unit is GATED on the bundle's presence: a node whose channel carries no bundle (a CAPRKE2 workload
  # node — joiners fetch the CA from the init server via the token) cleanly no-ops, and rke2
  # self-generates its CA on a bare mgmt survey — the pre-deterministic-PKI fallback, surfaced as a
  # skipped unit the rke2lab.target probe reports.
  systemd.services.sops-install-secrets = {
    unitConfig.ConditionPathExists = "/run/rke2lab/cluster-ca-bundle.yaml";
    after = [ "cloud-init.service" ];
    before = [ "rke2-server.service" ];
    wantedBy = [ "rke2-server.service" ];
  };
}
