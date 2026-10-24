# pulumi, mocked over files: `stack` = the stack exists, `instance` = its node exists, `outputs.json` = its exports.
# `stack init` writes the salt line into the working stack file, as pulumi does (`init-writes` replaces it).
m=${MOCK:?}
echo "pulumi $*" >>"$m/calls"
case $1 in
  stack)
    case $2 in
      output) [[ -e $m/stack ]] && cat "$m/outputs.json" || echo '{}' ;;
      ls) [[ -e $m/stack ]] && echo '[{"name":"nikopol"}]' || echo '[]' ;;
      rm) [[ -e $m/fail-stack-rm ]] && exit 1; rm -f "$m/stack" ;;
      init)
        touch "$m/stack"
        if [[ -e $m/init-writes ]]; then
          cat "$m/init-writes" >>"Pulumi.$3.yaml"
        else
          sed -i '1i encryptionsalt: v1:c2FsdA==:v1:AAAA:BBBB==' "Pulumi.$3.yaml"
        fi
        ;;
    esac
    ;;
  destroy)
    [[ -e $m/fail-destroy ]] && exit 1
    rm -f "$m/instance"
    ;;
esac
exit 0
