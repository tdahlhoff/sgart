-- Household display read model (CQRS, AD-4): projected from HouseholdCreated. No personal data —
-- the household name, not a person's (AC1).
CREATE TABLE household_read_model (
    household_id UUID         PRIMARY KEY,
    name         VARCHAR(120) NOT NULL
);

-- Membership link read model: projected from MemberJoined. member_id only — no keycloakUserId,
-- display name, or email (AD-5/AD-6).
CREATE TABLE household_membership_read_model (
    household_id UUID NOT NULL,
    member_id    UUID NOT NULL,
    PRIMARY KEY (household_id, member_id)
);
