# The #grow app, mocked: logs its call and the marker it was given; `up` creates the stack and the instance.
m=${MOCK:?}
echo "grow $* profile=${RKE2LAB_GROW_PROFILE:-}" >>"$m/calls"
[[ -e $m/fail-grow-$2 ]] && exit 1
if [[ $2 == up ]]; then
  touch "$m/stack" "$m/instance" "$m/project"
fi
exit 0
