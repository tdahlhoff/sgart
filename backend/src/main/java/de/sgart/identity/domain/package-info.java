/**
 * Domain layer — aggregates, entities, value objects, domain events, and ports. Pure: no framework, persistence, transport, or identity type appears here (AD-1). Enforced by the architecture test.
 *
 * <p><strong>AD-6 rev F (Story 8.3):</strong> {@link de.sgart.identity.domain.MembershipNickname}
 * is a documented, intentional exception to this context's "no persisted PII" rule — a person's
 * freely-chosen, per-household display name. It is never derived from a Keycloak claim and never
 * written into a domain event or projection. <em>Purpose:</em> letting household members recognise
 * each other. <em>Lawful basis:</em> performance of the service the person signed up for (GDPR
 * Art. 6(1)(b)) — they enter it themselves to be named in a household they chose to share.
 * <em>Retention:</em> exactly as long as the membership — deleted on leave, removal, and household
 * deletion, and on account erasure; the data-export path is still open (Epic 6, see
 * {@code deferred-work.md}). See {@link de.sgart.identity.domain.MembershipNicknameRepository},
 * {@code NoPersistedPersonalDataTest}, and the architecture spine's AD-6 rev F.
 */
package de.sgart.identity.domain;
