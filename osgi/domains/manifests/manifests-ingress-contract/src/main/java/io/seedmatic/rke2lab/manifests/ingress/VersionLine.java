package io.seedmatic.rke2lab.manifests.ingress;

/**
 * Where a {@code versions} report carries each component's line of the common version contract
 * (nix-flake-commons {@code nix/versions/schema.json}): the bumper attaches it to the component's
 * report step under {@link #ATTACHMENT_TITLE}, and the CLI's {@code format=jsonl} prints those
 * attachments alone. Here, in the dual-realm ingress, because the two sides are the OSGi scion and
 * the flat host, which share only this module.
 */
public final class VersionLine {

  public static final String ATTACHMENT_TITLE = "version-line";

  private VersionLine() {}
}
