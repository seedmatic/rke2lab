# incus, mocked over files: `instance`, `project`, `images` (one fingerprint per line), `instances.csv` (name,project
# lines for `list --all-projects`); `file pull` prints rke2.yaml. `exec` stands for a healthy throwaway node; each
# `node-*` file breaks one thing on it.
m=${MOCK:?}
echo "incus $*" >>"$m/calls"
case $1 in
  info) [[ -e $m/instance ]] ;;
  list)
    [[ -e $m/fail-list ]] && exit 1
    if [[ $2 == --all-projects ]]; then
      [[ -e $m/instances.csv ]] && cat "$m/instances.csv"
    elif [[ -e $m/instance ]]; then
      [[ -e $m/node-stopped ]] && echo "nikopol-mgmt-master,STOPPED,nikopol" || echo "nikopol-mgmt-master,RUNNING,nikopol"
    fi
    exit 0
    ;;
  exec)
    shift 4
    [[ $1 == -- ]] && shift
    case "$1 $2" in
      "systemctl is-active")
        [[ ${*: -1} == rke2-server.service ]] && { [[ ! -e $m/node-rke2-down ]]; exit; }
        [[ -e $m/node-socket-active && ${*: -1} == sshd.socket ]] && exit 0
        [[ -e $m/node-tailscaled && ${*: -1} == tailscaled.service ]] && exit 0
        exit 3
        ;;
      "systemctl list-units")
        [[ -e $m/node-failed-unit ]] && echo "rke2lab-identity.service loaded failed failed x"
        exit 0
        ;;
      "systemctl is-enabled")
        [[ -e $m/node-ssh-enabled && $3 == sshd.socket ]] && { echo enabled; exit 0; }
        echo masked-runtime
        ;;
      "test -e") [[ -e $m/node-has-${3//\//_} ]] ;;
      "find /run/secrets") [[ -e $m/node-run-secrets ]] && echo /run/secrets/x; exit 0 ;;
      "find /run/secrets.d") exit 0 ;;
      "find /home") [[ -e $m/node-home-keys ]] && echo /home/u/.ssh/authorized_keys; exit 0 ;;
      "ss -Hltn")
        [[ -e $m/node-listens-22 ]] && echo "LISTEN 0 128 0.0.0.0:22 0.0.0.0:*"
        exit 0
        ;;
      *) exit 1 ;;
    esac
    ;;
  file)
    [[ -e $m/fail-pull ]] && exit 1
    cat "$m/rke2.yaml"
    ;;
  project)
    case $2 in
      show) [[ -e $m/project ]] ;;
      delete) [[ -e $m/fail-project-delete ]] && exit 1; rm -f "$m/project" ;;
    esac
    ;;
  image)
    case $2 in
      list) [[ -e $m/images ]] && cat "$m/images"; exit 0 ;;
      delete) sed -i "/^$5\$/d" "$m/images" ;;
    esac
    ;;
esac
