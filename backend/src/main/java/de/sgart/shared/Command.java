package de.sgart.shared;

/**
 * A client-originated intention to change state — the envelope every concrete command implements
 * (AD-8). It is handled by an aggregate root that validates the invariant and emits one or more
 * {@link DomainEvent}s; nothing else mutates state.
 *
 * <p>The envelope carries a client-supplied {@link CommandId} (opaque; makes replay idempotent) and
 * a {@link AggregateVersion} {@code basedOnVersion} — the version of <em>the target aggregate root's
 * own</em> stream the command was built on, never a related aggregate's (AD-8). {@code basedOnVersion}
 * is the expected-version token passed to {@link EventStore#append}.
 *
 * <p>This is a pure contract with no framework or transport types (shared kernel); a kernel-purity
 * ArchUnit rule keeps it that way. It is defined once and reused unchanged by every later
 * command-emitting story.
 */
public interface Command {

    CommandId commandId();

    AggregateVersion basedOnVersion();
}
