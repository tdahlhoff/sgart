import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/households/data/household_summary.dart';
import 'package:sgart/features/households/presentation/manage_household_page.dart';
import 'package:sgart/features/invites/data/invites_api.dart';
import 'package:sgart/features/invites/data/pending_invite.dart';
import 'package:sgart/features/stores/data/store_chain_reference_cache.dart';
import 'package:sgart/features/stores/data/stores_api.dart';

import '../../../support/fake_invites_dependencies.dart';
import '../../../support/fake_stores_dependencies.dart';
import '../../../support/widget_test_harness.dart';

void main() {
  group('ManageHouseholdPage', () {
    late FakeStoresApi storesApi;
    late FakeStoreChainReferenceCache referenceCache;
    late FakeInvitesApi invitesApi;

    const household = HouseholdSummary(householdId: 'household-1', name: 'Familie Muster');

    setUp(() {
      storesApi = FakeStoresApi();
      referenceCache = FakeStoreChainReferenceCache();
      invitesApi = FakeInvitesApi();
    });

    Widget buildSubject() => wrapForTesting(
          MultiRepositoryProvider(
            providers: [
              RepositoryProvider<StoresApi>.value(value: storesApi),
              RepositoryProvider<StoreChainReferenceCache>.value(value: referenceCache),
              RepositoryProvider<InvitesApi>.value(value: invitesApi),
            ],
            child: const ManageHouseholdPage(household: household),
          ),
        );

    testWidgets('theInvitesRowOpensTheInvitePage', (tester) async {
      await tester.pumpWidget(buildSubject());

      await tester.tap(find.byKey(const Key('manage-invites-row')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('invite-email-field')), findsOneWidget);
    });

    testWidgets('sendingAnInviteFromTheInvitePageCallsTheBackendAndAppendsAPendingRow', (tester) async {
      await tester.pumpWidget(buildSubject());
      await tester.tap(find.byKey(const Key('manage-invites-row')));
      await tester.pumpAndSettle();

      await tester.enterText(find.byKey(const Key('invite-email-field')), 'anna@example.com');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('invite-send-button')));
      await tester.pumpAndSettle();

      expect(invitesApi.lastSentEmail, 'anna@example.com');
      expect(find.byKey(const Key('invites-pending-empty-state')), findsNothing);
    });

    testWidgets('thePendingInvitesListRendersWhatTheReadModelReturns', (tester) async {
      invitesApi.pendingInvitesToReturn = const [
        PendingInvite(inviteId: 'invite-1', invitedAt: '2026-09-06T10:00:00Z', invitedBy: 'member-1', status: 'PENDING'),
      ];
      await tester.pumpWidget(buildSubject());
      await tester.tap(find.byKey(const Key('manage-invites-row')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('invite-row-invite-1')), findsOneWidget);
    });
  });
}
