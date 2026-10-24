# kubectl, mocked: a healthy cluster when the kubeconfig names the expected server; each `kube-*` file breaks one thing.
m=${MOCK:?}
echo "kubectl $* KUBECONFIG=${KUBECONFIG:-}" >>"$m/calls"
grep -q 'server: https://nikopol-mgmt-master.nikopol:6443' "$KUBECONFIG" || exit 1
case $1 in
  get)
    if [[ $2 == --raw ]]; then
      [[ -e $m/fail-ready ]] && exit 1
      echo ok
    else
      [[ ${*} == *"k8s-app=kube-dns"* && -e $m/kube-dns-pending ]] && { echo "Running Pending"; exit 0; }
      echo "Running"
    fi
    ;;
  wait)
    [[ $3 == node/* && -e $m/kube-node-notready ]] && exit 1
    [[ $3 == pod/* && -e $m/kube-pod-fails ]] && exit 1
    exit 0
    ;;
  run) touch "$m/probe-pod" ;;
  delete) rm -f "$m/probe-pod" ;;
esac
