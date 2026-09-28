/**
 * Application layer — command handlers, query handlers, and process managers. Orchestrates the domain through ports; contains no business rules of its own.
 *
 * <p><strong>AD-6 rev F (Story 8.3):</strong> {@link de.sgart.identity.application.SetMembershipNickname}
 * and {@link de.sgart.identity.application.ResolveMembershipNicknames} are the only paths that write
 * and read the persisted per-household nickname — the documented exception to "no persisted PII"
 * (purpose, lawful basis, and retention are stated in the {@code identity.domain} package-info).
 * {@link de.sgart.identity.application.RetractMembership} deletes it together with the membership
 * mapping, so it never outlives the membership.
 */
package de.sgart.identity.application;
