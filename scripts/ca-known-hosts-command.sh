#!/usr/bin/env bash
# KnownHostsCommand script (@codebase)
# Emits one @cert-authority line per host authority public. Template variables replaced by Nix:
# @caDir@ holds the publics (files matching *-ca.pub), @hostNames@ is the fleet's names, comma-joined:
# the authority vouches for those hosts only, never for whichever host ssh asks about.

set -euo pipefail

# shellcheck disable=SC1091
source "@nixBashTrampoline@"

main() {
  local f key_type key_data
  shopt -s nullglob
  for f in "@caDir@"/*-ca.pub; do
    [[ -f "${f}" ]] || continue
    key_type="" key_data=""
    read -r key_type key_data _ <"${f}" || true
    case "${key_type}" in
      ssh-*|ecdsa-*|sk-*) ;;
      *) continue ;;
    esac
    [[ -n "${key_data}" ]] || continue
    echo "@cert-authority @hostNames@ ${key_type} ${key_data}"
  done
}

ndh::logger:command:run ssh.known-hosts-command main "$@"
