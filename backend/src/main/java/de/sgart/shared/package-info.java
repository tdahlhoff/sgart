/**
 * Shared kernel — cross-context value types with no business logic.
 *
 * <p>Holds the building blocks every context reuses: {@link de.sgart.shared.Money},
 * {@link de.sgart.shared.Quantity}, the opaque {@link de.sgart.shared.Identifier}, and the
 * {@link de.sgart.shared.ErrorDescriptor} error shape ({@code code / message / details}). These are
 * skeletal on purpose (KISS/YAGNI): later stories flesh them out when a concrete need arrives.
 *
 * <p>It also holds the cross-context <strong>write-side envelope</strong> (Story 1.5): the
 * {@link de.sgart.shared.Command} / {@link de.sgart.shared.DomainEvent} contracts, the
 * {@link de.sgart.shared.CommandId} / {@link de.sgart.shared.EventId} /
 * {@link de.sgart.shared.AggregateVersion} / {@link de.sgart.shared.StreamId} value types, the
 * {@link de.sgart.shared.EventSourcedAggregate} base, and the {@link de.sgart.shared.EventStore}
 * port with expected-version optimistic concurrency and {@code commandId} idempotent replay. This is
 * infrastructure-agnostic domain machinery, not business logic — every context's aggregates reuse
 * it, so it lives here rather than in any one context. The shared kernel depends on nothing else in
 * the system and on no framework or infrastructure type (AD-1, AD-2).
 */
package de.sgart.shared;
