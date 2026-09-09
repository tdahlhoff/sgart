/// The content-free "something changed" nudge (Story 4.4, LD-1): `householdId` + a coarse
/// `resource` hint (`list`/`trip`/`members`/`household`) — never item/list/trip/member content.
/// The client reacts by refetching through the existing GET queries; `resource` is a hint the
/// client may ignore and refetch its active screens regardless.
class HouseholdChangeNudge {
  const HouseholdChangeNudge({required this.householdId, required this.resource});

  final String householdId;
  final String resource;

  @override
  bool operator ==(Object other) =>
      other is HouseholdChangeNudge && other.householdId == householdId && other.resource == resource;

  @override
  int get hashCode => Object.hash(householdId, resource);

  @override
  String toString() => 'HouseholdChangeNudge(householdId: $householdId, resource: $resource)';
}
