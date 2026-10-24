# kubectl, mocked: `get --raw /readyz` answers ok when the kubeconfig names the expected server.
m=${MOCK:?}
echo "kubectl $* KUBECONFIG=${KUBECONFIG:-}" >>"$m/calls"
[[ -e $m/fail-ready ]] && exit 1
grep -q 'server: https://throwaway-nikopol-node.nikopol:6443' "$KUBECONFIG" && echo ok
