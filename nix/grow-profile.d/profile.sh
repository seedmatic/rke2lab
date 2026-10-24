# The grow's profile, decided from the COMMITTED stack file, and what it changes about the grow's inputs.
#
# Usage: rke2lab-grow-profile <stack>   (run in the rke2lab repo root, the grow's own working directory)
#
# Prints the profile on stdout: "fleet" or "throwaway". Exits non-zero, saying why on stderr, when the stack's
# committed config and the caller disagree.
#
# The throwaway profile (a kube API on nikopol for another project's tests) decrypts NOTHING: no CA is minted,
# no Secret sealed, no sops-age rendered, so it takes no age identity. It is read from the COMMITTED
# Pulumi.<stack>.yaml, never the working tree, and it is the profile only when the installed command's
# RKE2LAB_GROW_PROFILE agrees: neither side alone selects it, so a fleet stack can never be grown without its
# identity by a stray variable, nor the reverse. The JVM checks the same two again.

stack="${1:?usage: rke2lab-grow-profile <stack>}"
file="Pulumi.$stack.yaml"

profile=""
# A stack with no committed file is no throwaway. One yq cannot read stops the grow rather than reading as a
# fleet stack.
if committed="$(git show "HEAD:$file" 2>/dev/null)"; then
  profile="$(printf '%s\n' "$committed" | yq '.config."rke2lab:cluster".profile // ""')" || {
    echo "error: the committed $file cannot be read" >&2
    exit 2
  }
fi

case "$profile:${RKE2LAB_GROW_PROFILE:-}" in
  throwaway:throwaway)
    # pulumi reads the WORKING-TREE file: a throwaway stack is grown from its committed config only, so the two
    # cannot differ.
    if ! git diff --quiet HEAD -- "$file"; then
      echo "error: $file differs from HEAD — a throwaway stack is grown from its committed config only" >&2
      exit 2
    fi
    echo throwaway
    ;;
  throwaway:*)
    echo "error: stack $stack is a throwaway one — grow it with the installed rke2lab-throwaway command" >&2
    exit 2
    ;;
  *:?*)
    echo "error: RKE2LAB_GROW_PROFILE is set, but the committed $file is not a throwaway stack" >&2
    exit 2
    ;;
  *)
    echo fleet
    ;;
esac
