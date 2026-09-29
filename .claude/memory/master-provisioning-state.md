---
name: master-provisioning-state
description: "★ PROOF DONE against REAL master (2026-06-13): fixed the live-dbus-gate contract on branch fix/systemd-live-probe-contract (2 commits 08f8e3c1 fix + 57aaa9ae memory, NOT merged), then PROVED it end-to-end with a real `pulumi up` against a real induced incident. The gate used to THROW IllegalStateException at its deadlines (observationHolder stayed null → consultDoctor(null) early-returned → doctor NEVER consulted on a real run); now ensureReachable RETURNS Observation.failed(CONNECTION_REFUSED / TIMEOUT). Induced the incident by an nft block of dbus port 12434 (drop-in on dbus.socket holds the port, NOT the oneshot service — firewall was the safe lever); the real run consulted the doctor + rendered the loquacious runbook (⚕ Diagnosis / 🔬 Assessment dbus-tcp + ℞ Mitigation / 🔬 Assessment network with NO ℞) and DEGRADED-and-CONTINUED (severity=warning). nft rule REMOVED, master healthy again. NEXT TOPIC = the stack-history → health-system feedback loop (see body)."
metadata:
  node_type: memory
  type: project
  originSessionId: a1f0fd81-d8f4-478e-8043-510f2093c00b
---

**★ CONTRACT FIXED + PROVEN AGAINST REAL MASTER (2026-06-13, branch `fix/systemd-live-probe-contract`,
2 commits `08f8e3c1` fix + `57aaa9ae` memory, NOT merged):**
The live gate no longer throws at its deadlines — it returns the symptomatic Observation the
`SystemdAdapterProbe` javadoc promises (and the simulate/fake probes already honored):
- runtime/dbus deadline → `Observation.failed(Symptom.CONNECTION_REFUSED, lastSummary, …)` (dbus "why"
  preserved in details). Runtime snapshot stamps status=ok the instant dbus answers, so the deadline
  ≡ connection never succeeded.
- instance-unreachable deadline → `Observation.failed(Symptom.TIMEOUT, …)` (no instance-not-found
  Symptom; TIMEOUT is the honest map — distinct from a refused port).
HOW: `SeedSystemdAdapterEndpointGate` went from a static utility to an injectable INSTANCE (4 collaborators:
nanoClock/sleeper/runtimeProbe/instanceReachability), `production()` wires the live ones; `BootstrapPipeline`
builds it once and delegates. The seam made the deadline paths unit-testable (the untestability WAS the
design smell). RED test `SeedSystemdAdapterEndpointGateTest` (2 cases) failed by the throw, then GREEN. 152 green.

**THE REAL-MASTER PROOF (done this session):** induced the incident with an nft block of dbus port 12434
(KEY topology fact: the port is held by a drop-in on `dbus.socket` — `/etc/systemd/system/dbus.socket.d/
40-rke2lab-tcp.conf` ListenStream=…:12434 — NOT by the `rke2lab-dbus-tcp-system-bus.service` oneshot, which
only WRITES that drop-in once; so stopping the service does NOT close the port. Firewall = the safe lever:
`nft add table inet rke2lab_probe_block` + chain hook input + `tcp dport 12434 reject with tcp reset`).
Real `pulumi up` (file backend `file://…/.pulumi-state`, readiness PT1M, override warning): probe got real
Connection refused → `⚕ consulted with 283 prior visit(s)` (the line that NEVER appeared before the fix) →
loquacious runbook rendered to `seed-master/target/runbook/adoc/features/runbook.asciidoc` (⚕ Diagnosis /
🔬 Assessment dbus-tcp + ℞ Mitigation restart-unit / 🔬 Assessment network with NO ℞ = decline-with-a-why)
→ DEGRADED-and-CONTINUED. Run finished 2m5s (2× PT1M passes preview+update + seed-image rebuild — NOT a hang).
Proof copy at `/private/tmp/runbook-proof-degraded.asciidoc`. The fresh checkpoint `dev-1781346303774990000`
(history 283→288 entries) PERSISTS the consultationReport (assessment/prescription/symptom serialized in the
stack state, not just logged). nft rule REMOVED, port 12434 reachable again, master healthy.

**★ NEXT TOPIC (user insight, 2026-06-13, end of session — the live successor):** THE OPERATOR'S EXTERNAL
INTERVENTION IS NOT IN THE STACK HISTORY. We induced the incident (nft add) and repaired it (nft delete)
ENTIRELY out-of-band (ssh/nft, never via pulumi/stack-config). So the record will show symptom-present at
version N then symptom-ABSENT at N+1, but with NO recorded cause of the cure — and crucially the system can
NEVER learn that its own prescription (restart-unit) did NOT fix it; a different external action did. The
missing domain concept = the INTERVENTION / treatment-administered event as first-class, with PROVENANCE
(pulumi-engine vs operator-manual vs external-change) and linkage to symptom resolution. Without it,
efficacy correlation (`historyOf(symptom)`, `0 prior treatment(s) resolved it`) is BLIND to out-of-band
cures. Connects to [[doctor-remediation-model]] (loop closure = propose-in-record / dispose-in-stack-config /
up re-observes — but that assumes the fix is MEDIATED; an unmediated ssh fix escapes it) and
[[efficacy-first-prescription-provisional]]. Design question to brainstorm: mediate the operator action
(make the fix go through stack-config so it leaves a trace) vs declare it (record an out-of-band Intervention
with provenance so the next visit can correlate). Resume here — this is the natural step-2 driver.

**PARKING + FRAMING (user, same session):** do NOT solve now — note it, resolve it at the END of the
experimentation (stay in observe-mode). AND the user's load-bearing reframe: this gap is not a bug to plug,
it is **what will let us BUILD A NEW SPECIALIST**. The connection (mine, to confirm when we brainstorm): the
operator doing an out-of-band ssh/nft fix IS the most ad-hoc end of the recruit-a-specialist gradient
([[doctor-remediation-model]]: ad-hoc-Claude → codified specialist; here ad-hoc-OPERATOR → codified
specialist). Recording the Intervention with provenance is rung 1 (the system observes "operator did X
out-of-band, symptom resolved"); repeated observations codify that ad-hoc cure into a specialist that, on
symptom S, recruits/prescribes the historically-effective external action. The new specialist's DOMAIN is
different from DbusTcp/Network/Cluster: those read the in-run readiness Observation; this one reads the DELTA
across the intervention boundary (expected-vs-observed drift caused by external actors between runs). So the
missing Intervention-with-provenance type is simultaneously (a) what makes efficacy correlation honest and
(b) the data source a drift/provenance specialist would consult.

---

**THE GOAL (user's framing, 2026-06-13):** now that the referral round-trip is shipped to main
([[referral-roundtrip-state]]), prove it against the REAL master — start a fresh `pulumi up` on
master and watch our state update at runtime; the loquacious runbook (the doctor explaining the dbus
failure with both seams) IS the proof the work was good. The dbus-TCP "blocker" is not an obstacle to
fix-then-demo — it IS the `CONNECTION_REFUSED` driver this whole chunk was built around, so a run
that still fails on the dbus probe is exactly the demo… IF the doctor actually gets consulted.

**CONSTRAINT (CLAUDE.md):** `pulumi up` mutates the live system → the USER runs it; Claude proposes.
Claude may freely run `pulumi preview`, read-only probes (incus list, port checks, unit status), and
compilation/tests.

**FRESH FACTS verified read-only this session (supersede the old stale note):**
- `Pulumi.dev.yaml` has `rke2lab:policy.readiness.override.systemd-adapter: warning`. Intrinsic
  severity is also WARNING (`SystemdAdapterStage.INTRINSIC_SEVERITY`). So a current `pulumi up` should
  **degrade-and-CONTINUE** on the dbus failure, NOT abort — contradicting the old "exit 32 / hard
  fail" note (that was likely a CRITICAL override at the time, now warning). Good: the run won't die
  on the dbus, it'll keep going.
- dbus endpoint config: `dbusHost: bioskop-master`, `dbusPort: 12434`; readiness `timeout: PT1M`;
  incus project `rke2lab`, builderHost `bioskop-nixos` (probe reaches master via
  `ssh bioskop-nixos` then `incus exec`).

**★ ROOT-CAUSE HYPOTHESIS (strong, verify in Phase 3 — this is the load-bearing finding):**
On a REAL `pulumi up`, the doctor is NEVER consulted for the dbus failure, so NO Assessment /
NO loquacious runbook is produced. Trace (all file:line current as of 2026-06-13):
1. Live probe wired at `BootstrapPipeline.java:272-273` =
   `cfg -> SeedSystemdAdapterEndpointGate.ensureReachable(cfg, logger)`.
2. `ensureReachable` returns `Observation.ok(...)` on success or **THROWS `IllegalStateException`**
   on timeout/refusal (`SeedSystemdAdapterEndpointGate.java:121` and `:195`). It has NO
   `Observation.failed(CONNECTION_REFUSED, …)` return path. `DbusSystemdProbe.probe` likewise THROWS
   (`DbusSystemdProbe.java:124-127`).
3. `SystemdAdapterScenario.When.the_systemd_adapter_probe_runs()` does `observation = probe.probe(config)`
   — a throw propagates, `observation` never set.
4. `SystemdAdapterStage.launch()`: the probe wrapper sets `observationHolder[0]` only AFTER the
   underlying probe RETURNS; on throw it stays null → catch sets `captured = null` →
   `consultDoctor(null)` early-returns (`SystemdAdapterStage.java:194`, guard
   `observation == null || symptom().isEmpty()`).
⇒ The loquacious-failure path is reachable ONLY via preview-with-simulate today
(`SimulatedSystemdAdapterProbe.of` DOES return a proper `Observation.failed(CONNECTION_REFUSED,…)`),
NOT via a real failing `pulumi up`.

**THE LIKELY FIX (to make the proof materialize):** the live probe must convert a refused/timed-out
connection into `Observation.failed(Symptom.CONNECTION_REFUSED, summary, details)` instead of throwing
a bare exception — so the captured observation carries the symptom and the doctor consults on a real
run. This is a genuine boundary bug (the fake/simulate path and the live path diverge in their failure
contract — a uniformity violation). Confirm with a minimal Phase-3 test before fixing; then a real
`pulumi up` should render DbusTcp prescribing + Network declining-with-a-why on the failed node.
Mind: `SystemdAdapterProbe`'s own javadoc SAYS it returns a non-ok Observation carrying the symptom —
the live impl violates its own contract. That's the smoking gun.

**REFINED 2026-06-13 (snapshot() now read — pinpoints the throw):** `SeedSystemdAdapterRuntimeStatusSnapshot.snapshot()`
CATCHES the per-iteration `IllegalStateException` from `DbusSystemdProbe.probe` and returns a MAP with
`status="execution-error"` (`:43-49`) — so the per-iteration probe does NOT throw (that's what lets
`waitForRuntimeProbe` poll). The throw is ONE LEVEL UP, at the **deadline** of `waitForRuntimeProbe`
(`SeedSystemdAdapterEndpointGate.java:121`, after PT1M) and symmetrically `waitForInstanceReachable`
(`:195`, "instance not found"). Both of the OLD note's symptoms (instance-not-found AND
connection-refused) are these two deadline-throw paths — fully consistent.

**★ FIX SHAPE (the data for the why is already in hand):** at its deadline, `waitForRuntimeProbe`
holds `lastSnapshot` whose `summary` already carries the "Connection refused" text. So the clean fix is
NOT to fabricate an Observation — it's to **RETURN `Observation.failed(Symptom.CONNECTION_REFUSED,
lastSummary, detailsFromLastSnapshot)` instead of throwing** (`:121`). The symptom + why flow straight
into the captured observation → the doctor consults → loquacious runbook. NUANCE to decide in the fix
session: `waitForInstanceReachable`'s "instance not found" has no dedicated Symptom in the enum
(CONNECTION_REFUSED/TIMEOUT/KUBECONFIG_MISSING/CONTROLLER_NOT_READY/API_NOT_READY) — map it to TIMEOUT
or add one; that path is arguably a real infra-not-up case distinct from a refused port.

**STILL UNVERIFIED (next session, read-only):** is the dbus actually down right now? (live read-only
probe: ssh bioskop-nixos → incus exec master → `systemctl status rke2lab-dbus-tcp-system-bus.service`
+ check port 12434). The old scope-decision (trim master apps to vCluster-bootstrap minimum,
[[seed-vcluster]]) is ORTHOGONAL — don't conflate with the proof.

**WHERE WE STOPPED:** Phase 1 of [[systematic-debugging]] (root-cause investigation), read-only only,
NO fix attempted, NO `pulumi` run. Decided to save state + start a FRESH session (context was heavy:
referral round-trip design→plan→9-task execution→merge all happened first). Resume cold from this note.
[[hub:works-best-from-concrete-code]] [[hub:error-handling-layered-contract]] (the throw-vs-Observation choice
is exactly the leaf-throws-vs-typed-result tension).
