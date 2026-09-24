package io.seedmatic.rke2lab.controlplane.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares, on a config record, that its section must be joined with a named subtree of {@code
 * .secrets} before mapping — the merge directive OWNED by the type that consumes the secret, not by
 * {@code Pulumi.dev.yaml}. {@link ConfigLoader#bind(Class, String)} reads this annotation off the
 * bound type and deep-merges the named {@code .secrets} subtree into the section (its leaves win on
 * collision — sops is the authority) before Jackson maps it. So the contract between a record and
 * its secret source is stated in ONE place, beside the fields — change the record, the join moves
 * with it, no {@code Pulumi.dev.yaml}/record drift.
 *
 * <p>{@code from} is the dotted {@code .secrets} path (e.g. {@code "provider.credentials"}); the
 * section itself is the {@code section} argument the director passes to {@code bind}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SecretJoin {
  String from();
}
