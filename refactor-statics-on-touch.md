---
name: refactor-statics-on-touch
description: Boy-scout rule for static helpers — when a change touches a class that still carries a non-factory public static method, refactor that static into an instance in the SAME change, do not defer it to the WARN backlog.
metadata:
  type: feedback
---

When a change touches a class that still carries a non-factory `public static` (behaviour) method,
refactor that static into an instance **in the same change** — do not leave it for "later" or lean on
the INSTANCE_DISCIPLINE WARN backlog to remember it.

**Why:** the [[build-gates-over-review-reminders]] gate makes the whole static debt *visible*, but
visibility is not the same as paying it down — left alone the backlog at best stops growing, it never
shrinks. The standing rule is boy-scout: the moment a change *references* the class, refactor the
static then and there. The trigger is deliberate — when a change touches it you have the context
LOADED: you can see who calls it, with what data, what ROLE it plays in the system, so you know the
right instance to move it onto. Defer it and that context is lost; the refactor becomes expensive
again and the WARN count stays flat behind a comment. Touching = the one moment the move is cheap.

**A static inside a DAG is usually a DESIGN defect, not a style nit.** In a derivation/pipeline graph
(e.g. `ClusterNetworkBlueprint.derive()`), every node should derive from its parent node by following
object references. A static short-circuits a missing edge — it rebuilt+re-parsed an address string
(`inet("10.80."+octet+".1")`) instead of asking the `Cidr` it already held (`clusterCidr.gateway()`).
The static HID the absent edge; removing it surfaces the real graph and the right instance method to
add (`cidr.host(n)`/`gateway()`/`address(foreign)`). So "go all the way": don't relocate the static to
a better-placed static — move the behaviour onto the node that owns the data, restoring the edge. This
is [[object-graph-navigability-principle]] applied to a DAG: a static is an orphan node; in a DAG that
means a dependency that does not travel through references. (Pure string→value factories like
`Cidr.parse` stay static — they are construction, the entry edge of the graph, not an internal hop.)

**Finding the owner: there are usually SEVERAL candidates in scope, and the context may be
thread-local-carried.** The owner is rarely one obvious node. Two recurring shapes:

- *Several nodes at hand* — `derive()` held `clusterCidr`/`nodeCidr`/`vipCidr`/`lanCidr` all in scope;
  picking the right owner PER value (the network each address belongs to) is part of the refactor, not
  an afterthought.
- *Context carried by a ThreadLocal* — the natural owner is sometimes a context object delivered by a
  thread-local rather than passed in the signature (e.g. `ManifestSynthesisContext`, the thread-local
  runtime config injected into units). That context IS a legitimate instance to host the behaviour on;
  prefer it over wiring a brand-new instance through every call site. So a static YAML/util helper in
  `manifests-core` likely belongs on the already-thread-local-carried context, not on a fresh object.

**How to apply:**

- Touching a class with a static behaviour method ⇒ move it onto the natural instance (often one
  already sitting right next to the call site — e.g. `ConsultationNarration.consultedLine(record, sym)`
  had a twin `doctor.cohortFinding(sym)` one line below; the fix was `doctor.consultedLine(sym)`).
- Prefer the instance that already owns the data (instance-passing discipline,
  [[object-graph-navigability-principle]]): a render over a record's own fields belongs on that
  record or on the service that already exposes the record.
- After the move, drop the bundle's `@GovernedBy(INSTANCE_DISCIPLINE, WARN)` pose if that was its last
  static, so the bundle returns to the ERROR-locked default.
- Factories (`of`/`from*`/`parse`/`valueOf`/`builder`/`create`/`defaults`/`new*`, or returns-self) are
  NOT in scope — they are construction, exempt by the rule.
