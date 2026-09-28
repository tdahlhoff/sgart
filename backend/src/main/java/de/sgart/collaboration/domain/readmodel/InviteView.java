package de.sgart.collaboration.domain.readmodel;

import de.sgart.shared.InviteId;

/**
 * The household's single active invite code as held in the read model (AD-4, Story 8.4) — just the
 * id; no email, no status, no TTL (a reusable bearer capability with no lifecycle beyond "active"
 * or "replaced").
 */
public record InviteView(InviteId inviteId) {}
