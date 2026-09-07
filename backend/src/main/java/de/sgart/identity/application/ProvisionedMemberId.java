package de.sgart.identity.application;

import de.sgart.shared.MemberId;

/**
 * The outcome of {@link IssueMemberIdentity#provision}: the caller's {@link MemberId} together with
 * whether it was <em>freshly</em> generated ({@code true}) or replayed from an existing mapping
 * ({@code false}).
 *
 * <p>A handler that persists on its success path but can still fail <em>after</em> persisting (e.g.
 * {@code AcceptInviteHandler}, whose {@code append} can lose a concurrency race) uses {@link
 * #freshlyProvisioned()} to decide whether a compensating {@link IssueMemberIdentity#retract} is
 * safe: a freshly provisioned mapping written by this attempt may be rolled back, but an existing
 * member's real mapping must never be deleted (AD-5).
 */
public record ProvisionedMemberId(MemberId memberId, boolean freshlyProvisioned) {}
