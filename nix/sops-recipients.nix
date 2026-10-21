# rke2lab's .sops.yaml names its two standalone identities and nothing else: the operator's (age1hlvu…) and the cluster's
# (cluster-age 1, age18xsum…). In particular no HOST of ndh's keys.yaml: nothing here is read at a host's activation,
# so a host recipient would widen the blast radius for nothing (.sops.yaml says why). The hosts' recorded `age` are
# read from ndh's keys.json at the locked ndh revision, as a build: no IFD.
# Two mutants must be refused: a host recipient added, a standalone one removed.
{
  pkgs,
  sopsYaml,
  keysJson,
}:
let
  # Each with why it is here; the list a recipient must belong to.
  standalone = {
    "age1hlvu7l7kefeum8drzvc5jp0jhgsqw2h3t3a8rmzvx05lcrsmx48q2ehxsk" = "the operator: standalone, in no keys.yaml";
    "age18xsumfdy9lpvlmaxksty3m20u3gwjjg9uxhrd8upd86hv82c5dwqp3xtc9" = "rke2lab-cluster-age-1: standalone, the cluster's";
  };
  allowed = pkgs.writeText "allowed" (pkgs.lib.concatMapStrings (r: r + "\n") (builtins.attrNames standalone));
in
pkgs.runCommand "sops-recipients"
  {
    nativeBuildInputs = with pkgs; [
      coreutils
      diffutils
      gnugrep
      gnused
      yq-go
    ];
  }
  ''
    # The age recipients of one creation rule, sorted; comments left out (the former recipients are listed there).
    rule() { # <.sops.yaml> <index>
      yq -r ".creation_rules[$2].age // \"\" | split(\",\") | .[] | trim | select(. != \"\")" "$1" | sort -u
    }
    hosts=$(yq -p json -r '.keys | to_entries[] | select(.key | test("^host-")) | .value.slots // {} | .[] | .age // "" | select(. != "")' ${keysJson} | sort -u)
    [ -n "$hosts" ] || { echo "FAIL keys.json records no host age: ndh's K1 fields are missing at this ndh revision"; exit 1; }
    verify() { # <.sops.yaml>: every creation rule names exactly the two standalone identities, no host
      local n i r
      n=$(yq -r '.creation_rules | length' "$1")
      [ "$n" -gt 0 ] || { echo "FAIL $1 has no creation rule"; return 1; }
      for i in $(seq 0 $((n - 1))); do
        r=$(rule "$1" "$i")
        if found=$(grep -xF -f <(printf '%s\n' "$hosts") <<<"$r"); then
          echo "FAIL $1 rule $i names a host of ndh's keys.yaml: $found"
          return 1
        fi
        if ! diff <(sort -u ${allowed}) <(printf '%s\n' "$r") >/dev/null; then
          echo "FAIL $1 rule $i's recipients are not exactly the two standalone identities:"
          diff <(sort -u ${allowed}) <(printf '%s\n' "$r") | grep '^[<>]' | sed 's/^/  /'
          return 1
        fi
      done
    }
    verify ${sopsYaml}
    echo "ok   .sops.yaml names the operator and cluster-age 1 only, no host ($(wc -l <<<"$hosts") host ages read)"
    mutant() { # <name> <yq expression> <the refusal it must meet>
      yq "$2" ${sopsYaml} >mutant.yaml
      if why=$(verify mutant.yaml); then echo "FAIL mutant '$1' passed"; exit 1; fi
      case "$why" in
        *"$3"*) echo "ok   mutant '$1' refused: $3" ;;
        *) echo "FAIL mutant '$1' refused for another reason: $why"; exit 1 ;;
      esac
    }
    host=$(head -1 <<<"$hosts")
    mutant "a host recipient added" ".creation_rules[0].age += \",$host\"" "names a host of ndh's keys.yaml"
    mutant "a standalone one removed" '.creation_rules[].age |= sub(",?age18xsum[0-9a-z]+"; "")' "not exactly the two standalone identities"
    touch $out
  ''
