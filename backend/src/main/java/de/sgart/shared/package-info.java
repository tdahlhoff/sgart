/**
 * Shared kernel — cross-context value types with no business logic.
 *
 * <p>Holds the building blocks every context reuses: {@link de.sgart.shared.Money},
 * {@link de.sgart.shared.Quantity}, the opaque {@link de.sgart.shared.Identifier}, and the
 * {@link de.sgart.shared.ErrorDescriptor} error shape ({@code code / message / details}). These are
 * skeletal on purpose (KISS/YAGNI): later stories flesh them out when a concrete need arrives.
 * The shared kernel depends on nothing else in the system (AD-2).
 */
package de.sgart.shared;
