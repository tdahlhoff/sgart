package de.sgart.collaboration.application.command;

import de.sgart.collaboration.domain.HouseholdName;
import de.sgart.shared.AggregateVersion;
import de.sgart.shared.Command;
import de.sgart.shared.CommandId;
import java.util.Objects;

/**
 * The caller's intention to create a household with a chosen name (AC1). {@code basedOnVersion}
 * is always {@link AggregateVersion#initial} of the freshly generated household's own stream —
 * there is no prior version to build against for a brand-new aggregate (AD-8, AR10).
 */
public record CreateHousehold(CommandId commandId, AggregateVersion basedOnVersion, HouseholdName name)
        implements Command {

    public CreateHousehold {
        Objects.requireNonNull(commandId, "commandId must not be null");
        Objects.requireNonNull(basedOnVersion, "basedOnVersion must not be null");
        Objects.requireNonNull(name, "name must not be null");
        if (!basedOnVersion.isInitial()) {
            throw new IllegalArgumentException(
                    "CreateHousehold.basedOnVersion must be the initial version of a brand-new stream");
        }
    }
}
