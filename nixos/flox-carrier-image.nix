# The minimal OCI carrier image every flox-injected pod runs as its base — a SHARED LEAF,
# imported both by the NixOS module that BAKES it into the node (flox-carrier.nix) and by
# the flake that PUBLISHES its RepoTag for the manifest synthesis to read. The two must
# agree on the exact image, so the derivation lives once here rather than twice.
#
# WHY a nix-built carrier (not busybox:stable / alpine:latest): the flox NRI
# plugin overlays the host /nix/store + the env symlink farm into the container,
# then the pod command is `flox activate -- <cmd>`. The base image only has to
# provide (1) `/usr/bin/env` so portable `#!/usr/bin/env bash` shebangs resolve
# (busybox's env lacked GNU `-S` and forced shebang gymnastics), and (2) a shell
# + coreutils for `flox activate` itself. Everything else — bash, kdns, kubectl,
# delve — comes from the overlaid store on PATH (prod vs debug is a property of
# the flox ENV now, not the base image, so prod + debug share ONE carrier).
#
# WHY build it from nix: (a) removes the docker.io pull (the node is air-gapped);
# (b) pushes nix-store usage down into the container layers.
#
# ★ NO EXPLICIT TAG, deliberately: omitted, dockerTools tags the image with its OUTPUT
# HASH, so the tag MOVES WITH THE CONTENT. It used to be a literal `0.1.0` that a Java
# constant restated, with a comment pleading that the two "MUST match" — so a rebuilt
# carrier shipped different content under the same RepoTag, and a node that already held
# it could never adopt the new one (see flox-carrier.nix for why IfNotPresent makes that
# permanent). A derived tag makes the reference unwritable by hand, which is the point:
# consumers read it instead of restating it.
{ pkgs }:
pkgs.dockerTools.buildLayeredImage {
  name = "rke2lab/flox-carrier";
  contents = [
    pkgs.dockerTools.usrBinEnv # /usr/bin/env → coreutils env (the shebang target)
    pkgs.dockerTools.binSh # /bin/sh → shell for `flox activate`
    pkgs.bashInteractive # /bin/bash
    pkgs.coreutils # env, sleep, … (the debug sidecar's default Cmd)
  ];
  config = {
    Cmd = [ "/bin/sh" ];
    # nix-built tools find the CA bundle via SSL_CERT_FILE (real file below).
    Env = [
      "SSL_CERT_FILE=/etc/ssl/certs/ca-bundle.crt"
      "NIX_SSL_CERT_FILE=/etc/ssl/certs/ca-bundle.crt"
    ];
  };
  # /etc as REAL files, NOT store symlinks. `flox activate` does a getpwuid(0) at
  # startup → needs /etc/passwd, or it dies "ENOENT" before doing anything (proven
  # by strace). dockerTools.fakeNss / caCertificates would supply these — but as
  # SYMLINKS into /nix/store, and the flox NRI plugin overlays /nix/store with the
  # HOST store, which SHADOWS the image's own store paths → those symlinks DANGLE
  # in the container (cat /etc/passwd → ENOENT under the overlay). busybox shipped
  # these as real files; write them the same way so they survive the overlay. The
  # CA bundle is copied (content), not symlinked, for workloads' external HTTPS
  # (e.g. tailscale-client `tailscale up`).
  extraCommands = ''
    mkdir -p etc etc/ssl/certs
    printf 'root:x:0:0:root:/root:/bin/sh\nnobody:x:65534:65534:nobody:/nonexistent:/bin/sh\n' > etc/passwd
    printf 'root:x:0:\nnogroup:x:65534:\n' > etc/group
    printf 'passwd: files\ngroup: files\nhosts: files dns\n' > etc/nsswitch.conf
    cp ${pkgs.cacert}/etc/ssl/certs/ca-bundle.crt etc/ssl/certs/ca-bundle.crt
    # A world-writable /tmp: dockerTools images ship none, but injected scripts
    # (e.g. headplane agent-sync's `yq … > /tmp/config.$$.yaml`) expect it, and
    # the NRI overlay only covers /nix/store so it won't provide one either.
    mkdir -p tmp
    chmod 1777 tmp
  '';
}
