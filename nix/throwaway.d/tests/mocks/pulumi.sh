# pulumi, mocked over files: `stack` = the stack exists, `instance` = its node exists, `outputs.json` = its exports.
m=${MOCK:?}
echo "pulumi $*" >>"$m/calls"
case $1 in
  stack)
    case $2 in
      output) [[ -e $m/stack ]] && cat "$m/outputs.json" || echo '{}' ;;
      ls) [[ -e $m/stack ]] && echo '[{"name":"nikopol"}]' || echo '[]' ;;
      rm) [[ -e $m/fail-stack-rm ]] && exit 1; rm -f "$m/stack" ;;
    esac
    ;;
  destroy)
    [[ -e $m/fail-destroy ]] && exit 1
    rm -f "$m/instance"
    ;;
esac
exit 0
