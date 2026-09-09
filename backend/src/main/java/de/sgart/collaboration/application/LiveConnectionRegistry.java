package de.sgart.collaboration.application;

import de.sgart.collaboration.application.exception.LiveConnectionClosedException;
import de.sgart.shared.HouseholdId;
import de.sgart.shared.MemberId;

/**
 * Application-owned port over the live-sync connection registry (Story 4.4, T3) — the SSE
 * endpoint ({@code adapter.in}) registers/deregisters a member's connection here, and the live-sync
 * fan-out ({@code adapter.out}) broadcasts nudges and enforces "mapping = access" (AC3) by evicting
 * a de-linked member's connections through the same registry. Neither caller depends on the
 * other's package — both depend only on this port and {@link LiveConnection} (hexagonal
 * direction: {@code adapter.in}/{@code adapter.out} both -> {@code application}, never
 * cross-adapter).
 */
public interface LiveConnectionRegistry {

    /** Registers one member's connection for a household — a member may hold several (multi-device). */
    void register(HouseholdId householdId, MemberId memberId, LiveConnection connection);

    /** Removes one specific connection (normal completion/timeout/error) — other connections are untouched. */
    void deregister(HouseholdId householdId, MemberId memberId, LiveConnection connection);

    /**
     * Closes and removes every connection the member holds for the household (AC3) — used when the
     * fan-out observes {@code MemberRemoved}/{@code MemberLeft} for that member.
     */
    void evictMember(HouseholdId householdId, MemberId memberId);

    /** Closes and removes every connection anyone holds for the household (AC3, {@code HouseholdDeleted}). */
    void evictHousehold(HouseholdId householdId);

    /**
     * Pushes the content-free "changed" nudge (LD-1) to every connection registered for the
     * household. Tolerates a dead connection ({@link LiveConnectionClosedException}) by
     * deregistering it and continuing — one dead client never blocks the others (T3).
     */
    void broadcast(HouseholdId householdId, String resource);
}
