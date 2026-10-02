# The `relock` app, as a FACTORY — because the rule must be ONE implementation, not a copy per repo.
#
# The app NAME is already decided to be identical in every repo
# (docs/architecture/patterns/flake-lock-propagation.adoc § naming): propagation is a REQUEST, so the
# caller must know nothing about the callee beyond its name. That argument does not stop at the name.
# If each repo wrote its own relock, "every repo applies the same rule" would be a claim nobody could
# check, and the two would drift exactly where it matters — the derivation-impact guard that
# TERMINATES the rke2lab <-> ndh cycle. So the rule lives once, here, and each repo supplies only what
# is its own.
#
# ndh already consumes `inputs.rke2lab.lib.*` (networkBlueprint, dataplan), so exporting this adds no
# edge and no new cycle: it rides the seam that is already there.
#
# A separate file rather than a `let` binding, because the two consumers sit in different scopes —
# rke2lab's own app is per-system (it needs `pkgs`), while `lib` is the system-independent export ndh
# reads. A file is visible to both without making either recursive.
{
  # The consumer's own nixpkgs — the app is system-specific even though this factory is not.
  pkgs,
  # Short repo name, for the log lines and the default input name.
  name,
  # `owner/name` — how a checkout is RECOGNISED as this repo. Matched on the slug and not the url
  # because a local checkout may speak ssh where the input speaks https, and the same repo must not
  # read as a different one because of the transport.
  slug,
  # Where to clone when the request arrives from outside our own checkout.
  url,
  # Who to REQUEST on `--downstream`. This is the one fact a repo cannot read off its own lock: a lock
  # says who I consume, never who consumes me.
  consumers ? [ ],
  # Extra pathspecs the dirty-guard must ignore, because relock rewrites them itself. `flake.lock` is
  # always ignored; these are the repo's generated artifacts (dataplan.json, …).
  ownedArtifacts ? [ ],
  # An orphan branch to push BEFORE inputs resolve — a `github:` input sees only what is pushed. "" for
  # a repo with none.
  pushFirstBranch ? "",
  # An orphan branch that PINS this repo and carries the flox envs. "" for a repo with none, and the
  # whole catalog hop is then skipped.
  catalogBranch ? "",
  # This repo's input name inside that catalog's lock.
  selfPinName ? name,
}:
pkgs.writeShellApplication {
  name = "relock";
  runtimeInputs = [
    pkgs.coreutils
    pkgs.git
    pkgs.jq
    pkgs.nix
  ];
  text = builtins.readFile (
    pkgs.replaceVars ./relock.sh {
      inherit
        selfPinName
        pushFirstBranch
        catalogBranch
        ;
      repoUrl = url;
      repoName = name;
      repoSlug = slug;
      system = pkgs.stdenv.hostPlatform.system;
      consumers = pkgs.lib.concatStringsSep " " (map (c: ''"${c}"'') consumers);
      ownedArtifacts = pkgs.lib.concatStringsSep " " (map (a: "':!${a}'") ownedArtifacts);
    }
  );
}
