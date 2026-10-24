# incus, mocked over files: `instance`, `project`, `images` (one fingerprint per line); `file pull` prints rke2.yaml.
m=${MOCK:?}
echo "incus $*" >>"$m/calls"
case $1 in
  info) [[ -e $m/instance ]] ;;
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
