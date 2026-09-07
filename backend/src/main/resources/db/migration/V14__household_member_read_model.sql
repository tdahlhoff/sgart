-- The member-roster read model (Story 4.3, AC8), projected from MemberJoined/MemberPromoted/
-- MemberDemoted/MemberLeft/MemberRemoved/HouseholdDeleted. No PII column (AD-6, decision 5): only
-- the pseudonymous member_id and role. NoPersistedPersonalDataTest keeps enforcing this.
CREATE TABLE household_member_read_model (
    household_id UUID         NOT NULL,
    member_id    UUID         NOT NULL,
    role         VARCHAR(20)  NOT NULL,
    PRIMARY KEY (household_id, member_id)
);
