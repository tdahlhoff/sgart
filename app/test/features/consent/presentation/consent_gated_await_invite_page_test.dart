import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/consent/data/consent_api.dart';
import 'package:sgart/features/consent/presentation/consent_gated_await_invite_page.dart';
import 'package:sgart/features/households/presentation/await_invite_page.dart';
import 'package:sgart/features/households/presentation/households_cubit.dart';
import 'package:sgart/features/invites/data/invite_link.dart';
import 'package:sgart/features/invites/data/invites_api.dart';
import 'package:sgart/shared/errors/app_error.dart';
import 'package:sgart/shared/http/app_exception.dart';

import '../../../support/fake_consent_dependencies.dart';
import '../../../support/fake_households_dependencies.dart';
import '../../../support/fake_invites_dependencies.dart';
import '../../../support/widget_test_harness.dart';

void main() {
  group('ConsentGatedAwaitInvitePage', () {
    late FakeConsentApi consentApi;
    late FakeInvitesApi invitesApi;
    late FakeHouseholdsApi householdsApi;
    late HouseholdsCubit householdsCubit;

    setUp(() {
      consentApi = FakeConsentApi();
      invitesApi = FakeInvitesApi();
      householdsApi = FakeHouseholdsApi();
      householdsCubit =
          HouseholdsCubit(householdsApi: householdsApi, activeHouseholdStore: FakeActiveHouseholdStore());
    });

    tearDown(() async {
      await householdsCubit.close();
    });

    // A placeholder first route with a button that opens the gated deep-link accept page — the
    // ConsentApi/InvitesApi/HouseholdsCubit it reads are provided above the Navigator.
    Widget buildHost() => wrapForTesting(
          MultiRepositoryProvider(
            providers: [
              RepositoryProvider<ConsentApi>.value(value: consentApi),
              RepositoryProvider<InvitesApi>.value(value: invitesApi),
            ],
            child: BlocProvider<HouseholdsCubit>.value(
              value: householdsCubit,
              child: Navigator(
                onGenerateRoute: (_) => MaterialPageRoute(
                  builder: (context) => Scaffold(
                    body: Center(
                      child: ElevatedButton(
                        key: const Key('open-gated'),
                        onPressed: () => openConsentGatedAwaitInvitePage(
                          context,
                          link: const InviteLink(householdId: 'household-1', inviteId: 'invite-1'),
                        ),
                        child: const Text('open'),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        );

    testWidgets('showsTheFailurePageWhenTheConsentStatusLoadFails_andRetryRecoversToTheGate',
        (tester) async {
      consentApi.getStatusError = const AppException(AppError(code: 'network.unreachable', message: 'debug'));

      await tester.pumpWidget(buildHost());
      await tester.tap(find.byKey(const Key('open-gated')));
      await tester.pumpAndSettle();

      // The load failed → the shared failure page, not the gate and not the accept screen.
      expect(find.byKey(const Key('consent-gate-load-error')), findsOneWidget);
      expect(find.byType(AwaitInvitePage), findsNothing);
      expect(invitesApi.acceptCallCount, 0);

      // The transient error clears; retry reloads the status → the gate is shown.
      consentApi.getStatusError = null;
      consentApi.statusToReturn =
          const ConsentStatus(accepted: false, acceptedVersion: null, currentVersion: '2026-beta-1');
      await tester.tap(find.byKey(const Key('consent-gate-retry-button')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('consent-gate-heading')), findsOneWidget);
      expect(find.byKey(const Key('consent-gate-load-error')), findsNothing);
    });

    testWidgets('showsTheAcceptScreenWhenConsentIsAlreadyRecorded', (tester) async {
      consentApi.statusToReturn = const ConsentStatus(
        accepted: true,
        acceptedVersion: '2026-beta-1',
        currentVersion: '2026-beta-1',
      );

      await tester.pumpWidget(buildHost());
      await tester.tap(find.byKey(const Key('open-gated')));
      await tester.pumpAndSettle();

      // Consent already recorded → straight to the accept screen (which auto-accepts the initial
      // link), gate never shown. The successful accept then routes away, so we assert the accept was
      // reached rather than the gate.
      expect(invitesApi.acceptCallCount, 1);
      expect(invitesApi.lastAcceptedHouseholdId, 'household-1');
      expect(invitesApi.lastAcceptedInviteId, 'invite-1');
      expect(find.byKey(const Key('consent-gate-heading')), findsNothing);
    });
  });
}
