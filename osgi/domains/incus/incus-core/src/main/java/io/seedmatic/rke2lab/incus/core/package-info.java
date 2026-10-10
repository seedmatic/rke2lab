/**
 * The incus domain's in-container LOGIC — pure Java driven by the incus scenarios: the grow-view
 * projectors ({@link io.seedmatic.rke2lab.incus.core.GrowNetworkResolver} / {@link
 * io.seedmatic.rke2lab.incus.core.GrowIdentityResolver} / {@link
 * io.seedmatic.rke2lab.incus.core.GrowPlanAssembler} the provision scion assembles the {@code
 * InstanceGrowPlan} from). The wire-records these read/write live in {@code incus-contract}; this
 * module holds only the logic. The former host-tree slot-rotation / staging→live promotion moved to
 * git (the rendered-branch model) and was removed.
 */
@org.osgi.annotation.versioning.Version("1.0.0")
@org.jspecify.annotations.NullMarked
package io.seedmatic.rke2lab.incus.core;
