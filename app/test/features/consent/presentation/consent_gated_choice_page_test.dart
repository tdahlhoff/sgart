import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/auth/data/device_credential_store.dart';
import 'package:sgart/features/auth/presentation/auth_cubit.dart';
import 'package:sgart/features/consent/data/consent_api.dart';
import 'package:sgart/features/consent/presentation/consent_gated_choice_page.dart';
import 'package:sgart/features/households/data/households_api.dart';
import 'package:sgart/features/households/presentation/households_cubit.dart';
import 'package:sgart/features/invites/data/invites_api.dart';
import 'package:sgart/features/stores/data/store_chain_reference_cache.dart';
import 'package:sgart/features/stores/data/stores_api.dart';
import 'package:sgart/shared/errors/app_error.dart';
import 'package:sgart/shared/http/app_exception.dart';

import '../../../support/fake_auth_dependencies.dart';
import '../../../support/fake_consent_dependencies.dart';
import '../../../support/fake_households_dependencies.dart';
import '../../../support/fake_invites_dependencies.dart';
import '../../../support/fake_stores_dependencies.dart';
import '../../../support/widget_test_harness.dart';

void main() {
  group('ConsentGatedChoicePage', () {
    late FakeConsentApi consentApi;
    late FakeHouseholdsApi householdsApi;
    late HouseholdsCubit householdsCubit;
    late FakeStoresApi storesApi;
    late FakeStoreChainReferenceCache referenceCache;
    late FakeInvitesApi invitesApi;
    late FakeDeviceCredentialStore deviceCredentialStore;
    late AuthCubit authCubit;

    setUp(() async {
      consentApi = FakeConsentApi();
      householdsApi = FakeHouseholdsApi()..createdHouseholdIdToReturn = 'hh-1';
      householdsCubit =
          HouseholdsCubit(householdsApi: householdsApi, activeHouseholdStore: FakeActiveHouseholdStore());
      storesApi = FakeStoresApi();
      referenceCache = FakeStoreChainReferenceCache();
      invitesApi = FakeInvitesApi();
      deviceCredentialStore = FakeDeviceCredentialStore()..wordsToReturn = List.generate(24, (i) => 'word$i');
      authCubit = await buildAuthenticatedAuthCubit();
    });

    tearDown(() async {
      await householdsCubit.close();
      await authCubit.close();
    });

    Widget buildSubject() => wrapForTesting(
          MultiRepositoryProvider(
            providers: [
              RepositoryProvider<ConsentApi>.value(value: consentApi),
              RepositoryProvider<HouseholdsApi>.value(value: householdsApi),
              RepositoryProvider<StoresApi>.value(value: storesApi),
              RepositoryProvider<StoreChainReferenceCache>.value(value: referenceCache),
              RepositoryProvider<InvitesApi>.value(value: invitesApi),
              RepositoryProvider<DeviceCredentialStore>.value(value: deviceCredentialStore),
            ],
            child: BlocProvider<HouseholdsCubit>.value(
              value: householdsCubit,
              child: BlocProvider<AuthCubit>.value(
                value: authCubit,
                child: const ConsentGatedChoicePage(),
              ),
            ),
          ),
        );

    // Test Manifest: consentGate_blocksCreateAndJoinUntilAccepted (AC1).
    testWidgets('consentGate_blocksCreateAndJoinUntilAccepted', (tester) async {
      consentApi.statusToReturn =
          const ConsentStatus(accepted: false, acceptedVersion: null, currentVersion: '2026-beta-1');

      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('consent-gate-heading')), findsOneWidget);
      expect(find.byKey(const Key('create-household-choice-button')), findsNothing);
      expect(find.byKey(const Key('await-invite-choice-button')), findsNothing);

      await tester.tap(find.byKey(const Key('consent-gate-accept-button')));
      await tester.pumpAndSettle();

      expect(consentApi.lastAcceptedVersion, '2026-beta-1');
      expect(find.byKey(const Key('consent-gate-heading')), findsNothing);
      expect(find.byKey(const Key('create-household-choice-button')), findsOneWidget);
      expect(find.byKey(const Key('await-invite-choice-button')), findsOneWidget);
    });

    // Test Manifest: consentGate_notShownWhenCurrentVersionAlreadyAccepted (AC4).
    testWidgets('consentGate_notShownWhenCurrentVersionAlreadyAccepted', (tester) async {
      consentApi.statusToReturn = const ConsentStatus(
        accepted: true,
        acceptedVersion: '2026-beta-1',
        currentVersion: '2026-beta-1',
      );

      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('consent-gate-heading')), findsNothing);
      expect(find.byKey(const Key('create-household-choice-button')), findsOneWidget);
    });

    // Test Manifest: consentGate_shownAgainWhenNoticeVersionBumped (AC4, D-D/D-E).
    testWidgets('consentGate_shownAgainWhenNoticeVersionBumped', (tester) async {
      consentApi.statusToReturn = const ConsentStatus(
        accepted: true,
        acceptedVersion: '2026-beta-1',
        currentVersion: '2026-beta-2',
      );

      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('consent-gate-heading')), findsOneWidget);
      expect(find.byKey(const Key('create-household-choice-button')), findsNothing);
    });

    // Test Manifest: createFlow_serverConsentRequired_surfacesTheGate (AC3).
    testWidgets('createFlow_serverConsentRequired_surfacesTheGate', (tester) async {
      // Client believes it is accepted (so the gate is not shown on entry — the stale-client
      // scenario) but the server rejects the create with 409 consent.required.
      consentApi.statusToReturn = const ConsentStatus(
        accepted: true,
        acceptedVersion: '2026-beta-1',
        currentVersion: '2026-beta-1',
      );
      householdsApi.createErrorToThrow =
          const AppException(AppError(code: 'consent.required', message: 'debug'));

      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();
      expect(find.byKey(const Key('create-household-choice-button')), findsOneWidget);

      await tester.tap(find.byKey(const Key('create-household-choice-button')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const Key('onboarding-name-field')), 'Rita & Werner');
      // A subsequent GET (triggered by the reload the failure listener fires) reports not-accepted
      // — the realistic server state a genuine consent.required rejection implies.
      consentApi.statusToReturn =
          const ConsentStatus(accepted: false, acceptedVersion: null, currentVersion: '2026-beta-1');
      await tester.tap(find.byKey(const Key('onboarding-name-next-button')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('consent-gate-heading')), findsOneWidget);
      expect(find.byKey(const Key('onboarding-name-field')), findsNothing);
    });
  });
}
