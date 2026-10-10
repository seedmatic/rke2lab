package io.seedmatic.rke2lab.fabric.bdd;

/**
 * The typed failure of a fabric delivery — a routing symptom rather than a bare string, so a run
 * that cannot deliver says WHICH link of the chain is missing. Every reason is a broken run, never
 * a degraded one: the delivery exists to publish what the run derived, so nothing to publish is a
 * defect upstream, not an empty result.
 */
public final class FabricDeliveryError extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  /** Which link was missing. */
  public enum Reason {
    /** No parcel: the run published none, so there is no plot to read a harvest from. */
    NO_PARCEL("the run published no parcel — nothing harvested a plan to deliver"),
    /** The netplan scion did not file its projection in this run. */
    NO_NETPLAN("the netplan projection was not harvested in this run"),
    /** The dataplan scion did not file its layout in this run. */
    NO_DATAPLAN("the dataplan layout was not harvested in this run"),
    /** No worktree factory: the delivery cannot be written without the git mechanism. */
    NO_WORKTREES("the worktree domain published no LinkedWorktrees — cannot make the delivery");

    private final String message;

    Reason(String message) {
      this.message = message;
    }

    public String message() {
      return message;
    }
  }

  private final Reason reason;

  public FabricDeliveryError(Reason reason) {
    super(reason.message());
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
