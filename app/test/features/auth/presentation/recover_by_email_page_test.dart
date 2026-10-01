import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/auth/data/account_email_api.dart';
import 'package:sgart/features/auth/data/caller_identity.dart';
import 'package:sgart/features/auth/data/device_credential_store.dart';
import 'package:sgart/features/auth/data/oidc_tokens.dart';
import 'package:sgart/features/auth/data/recovery_confirmation.dart';
import 'package:sgart/features/auth/presentation/auth_cubit.dart';
import 'package:sgart/features/auth/presentation/auth_state.dart';
import 'package:sgart/features/auth/presentation/recover_by_email_page.dart';
import 'package:sgart/features/auth/presentation/recovery_token_reveal_page.dart';
import 'package:sgart/shared/errors/app_error.dart';
import 'package:sgart/shared/http/app_exception.dart';

import '../../../support/fake_account_email_api.dart';
import '../../../support/fake_auth_dependencies.dart';
import '../../../support/fake_households_dependencies.dart';
import '../../../support/widget_test_harness.dart';

/// Test Manifest: recoverByEmail_validCode_rebindsSwapsIdentityAndRevealsFreshToken,
/// recoverByEmail_wrongCode_showsInlineErrorAndKeepsSessionIntact,
/// recoverByEmailPath_sendsNoLocalSecretItShouldNot (Story 7.3, AC2/AC3).
void main() {
  group('RecoverByEmailPage', () {
    late FakeOidcClient oidcClient;
    late FakeSecureTokenStorage tokenStorage;
    late FakeIdentityApi identityApi;
    late FakeDeviceCredentialStore deviceCredentialStore;
    late FakeActiveHouseholdStore activeHouseholdStore;
    late FakeAccountEmailApi accountEmailApi;
    late AuthCubit authCubit;

    setUp(() async {
      oidcClient = FakeOidcClient()..tokensToReturn = const OidcTokens(accessToken: 'access-throwaway');
      tokenStorage = FakeSecureTokenStorage();
      identityApi = FakeIdentityApi()
        ..identityToReturn = const CallerIdentity(
            keycloakUserId: 'sub-throwaway', displayName: 'Throwaway');
      deviceCredentialStore = FakeDeviceCredentialStore()..tokenToReturn = fakeRecoveryToken;
      activeHouseholdStore = FakeActiveHouseholdStore()..activeId = 'throwaway-household';
      accountEmailApi = FakeAccountEmailApi();
      authCubit = AuthCubit(
        oidcClient: oidcClient,
        tokenStorage: tokenStorage,
        identityApi: identityApi,
        deviceCredentialStore: deviceCredentialStore,
        activeHouseholdStore: activeHouseholdStore,
      );
      // Mirrors production: the app is already signed in as the throwaway account by the time the
      // "recover by email" quiet action is reachable (Story 7.1 invariant).
      await authCubit.signIn();
    });

    tearDown(() => authCubit.close());

    // Mirrors openRecoverByEmailPage's re-provision, using fakes instead of a real
    // AuthenticatedHttpClient (no network in widget tests, CLAUDE.md §6).
    Widget buildSubject() => wrapForTesting(
          BlocProvider<AuthCubit>.value(
            value: authCubit,
            child: RepositoryProvider<DeviceCredentialStore>.value(
              value: deviceCredentialStore,
              child: Builder(
                builder: (context) => Scaffold(
                  body: Center(
                    child: TextButton(
                      key: const Key('open-recover-by-email'),
                      onPressed: () => Navigator.of(context).push(
                        MaterialPageRoute<void>(
                          builder: (_) => MultiRepositoryProvider(
                            providers: [
                              RepositoryProvider<DeviceCredentialStore>.value(value: deviceCredentialStore),
                              RepositoryProvider<AccountEmailApi>.value(value: accountEmailApi),
                            ],
                            child: BlocProvider<AuthCubit>.value(
                              value: authCubit,
                              child: const RecoverByEmailPage(),
                            ),
                          ),
                        ),
                      ),
                      child: const Text('open'),
                    ),
                  ),
                ),
              ),
            ),
          ),
        );

    Future<void> openPage(WidgetTester tester) async {
      await tester.pumpWidget(buildSubject());
      await tester.tap(find.byKey(const Key('open-recover-by-email')));
      await tester.pumpAndSettle();
    }

    Future<void> requestCode(WidgetTester tester, String email) async {
      await tester.enterText(find.byKey(const Key('recover-by-email-email-field')), email);
      await tester.tap(find.byKey(const Key('recover-by-email-request-button')));
      await tester.pumpAndSettle();
    }

    testWidgets('recoverByEmail_validCode_rebindsSwapsIdentityAndRevealsFreshToken', (tester) async {
      await openPage(tester);
      await requestCode(tester, 'anna@example.test');

      // The rebind resolves into the *recovered* account — a different identity than the
      // throwaway one this cubit started as (mirrors recoverFromToken's precedent).
      oidcClient.tokensToReturn = const OidcTokens(accessToken: 'access-recovered');
      identityApi.identityToReturn = const CallerIdentity(
          keycloakUserId: 'sub-recovered', displayName: 'Recovered Person');

      await tester.enterText(find.byKey(const Key('recover-by-email-code-field')), '042817');
      await tester.tap(find.byKey(const Key('recover-by-email-confirm-button')));
      await tester.pumpAndSettle();

      expect(accountEmailApi.confirmedRecoveries, [('anna@example.test', '042817')]);
      expect(authCubit.state.status, AuthStatus.authenticated);
      expect(authCubit.state.keycloakUserId, 'sub-recovered');
      expect(activeHouseholdStore.cleared, isTrue);
      // The route was replaced (pushReplacement), not merely covered — this page is gone.
      expect(find.byType(RecoverByEmailPage), findsNothing);
      expect(find.byType(RecoveryTokenRevealPage), findsOneWidget);
      expect(find.byKey(const Key('recovery-token-value')), findsOneWidget);
      expect(
          find.text('Dein vorheriger Wiederherstellungsschlüssel gilt nicht mehr. Sichere diesen neuen Schlüssel.'),
          findsOneWidget);
    });

    testWidgets('recoverByEmail_wrongCode_showsInlineErrorAndKeepsSessionIntact', (tester) async {
      await openPage(tester);
      await requestCode(tester, 'anna@example.test');
      accountEmailApi.confirmRecoveryErrorToThrow =
          const AppException(AppError(code: 'account.recoveryCodeInvalid', message: 'wrong code'));

      await tester.enterText(find.byKey(const Key('recover-by-email-code-field')), '000000');
      await tester.tap(find.byKey(const Key('recover-by-email-confirm-button')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('recover-by-email-error')), findsOneWidget);
      // The underlying (throwaway) session is left fully intact — no inProgress/failure emit tore
      // it down (the 7.2 Review Finding #1 discipline, reapplied here).
      expect(find.byType(RecoverByEmailPage), findsOneWidget);
      expect(authCubit.state.status, AuthStatus.authenticated);
      expect(authCubit.state.keycloakUserId, 'sub-throwaway');
      expect(activeHouseholdStore.cleared, isFalse);
    });

    testWidgets('recoverByEmail_unregisteredEmail_stillAdvancesToTheCodeStep', (tester) async {
      // D-H: the server never reveals whether the email matched — this page never learns either,
      // so it always advances.
      await openPage(tester);

      await requestCode(tester, 'nobody@example.test');

      expect(find.byKey(const Key('recover-by-email-code-field')), findsOneWidget);
      expect(accountEmailApi.requestedRecoveryEmails, ['nobody@example.test']);
    });

    testWidgets('recoverByEmailPath_sendsNoLocalSecretItShouldNot', (tester) async {
      await openPage(tester);
      await requestCode(tester, 'anna@example.test');

      await tester.enterText(find.byKey(const Key('recover-by-email-code-field')), '042817');
      await tester.tap(find.byKey(const Key('recover-by-email-confirm-button')));
      await tester.pumpAndSettle();

      // The API only ever receives the plain email and the 6-digit code the person typed — never
      // any device credential/key material (AccountEmailApi's own interface shape already
      // structurally forbids that; this proves the page never routes around it).
      expect(accountEmailApi.requestedRecoveryEmails, ['anna@example.test']);
      expect(accountEmailApi.confirmedRecoveries, [('anna@example.test', '042817')]);
    });

    group('when the mailbox is bound to several accounts', () {
      const accountWithTwoHouseholds = RecoveryCandidate(accountId: 'account-two-households', households: [
        RecoveryCandidateHousehold(householdName: 'Familie Beispiel', nickname: 'Anna'),
        RecoveryCandidateHousehold(householdName: 'WG Testweg', nickname: 'Anni'),
      ]);
      const accountWithoutHousehold = RecoveryCandidate(accountId: 'account-no-household', households: []);

      Future<void> confirmCode(WidgetTester tester) async {
        await tester.enterText(find.byKey(const Key('recover-by-email-code-field')), '042817');
        await tester.tap(find.byKey(const Key('recover-by-email-confirm-button')));
        await tester.pumpAndSettle();
      }

      testWidgets('showsThePickerOnlyWhenSeveralAccountsMatch', (tester) async {
        await openPage(tester);
        await requestCode(tester, 'anna@example.test');
        accountEmailApi.confirmationToReturn =
            const RecoveryConfirmationChooseAccount([accountWithTwoHouseholds, accountWithoutHousehold]);

        await confirmCode(tester);

        expect(find.byKey(const Key('recover-by-email-account-subtitle')), findsOneWidget);
        expect(find.text('Familie Beispiel (Anna)'), findsOneWidget);
        expect(find.text('WG Testweg (Anni)'), findsOneWidget);
        expect(find.text('Konto ohne Haushalt'), findsOneWidget);
        // Nothing was rebound yet: the throwaway session is untouched.
        expect(authCubit.state.keycloakUserId, 'sub-throwaway');
        expect(activeHouseholdStore.cleared, isFalse);
      });

      testWidgets('showsNoPickerWhenASingleAccountMatches', (tester) async {
        await openPage(tester);
        await requestCode(tester, 'anna@example.test');

        await confirmCode(tester);

        expect(find.byKey(const Key('recover-by-email-account-subtitle')), findsNothing);
      });

      testWidgets('preselectsTheAccountWithTheMostHouseholds', (tester) async {
        await openPage(tester);
        await requestCode(tester, 'anna@example.test');
        accountEmailApi.confirmationToReturn =
            const RecoveryConfirmationChooseAccount([accountWithTwoHouseholds, accountWithoutHousehold]);
        await confirmCode(tester);

        await tester.tap(find.byKey(const Key('recover-by-email-account-confirm-button')));
        await tester.pumpAndSettle();

        expect(accountEmailApi.confirmedRecoveryAccountIds.last, 'account-two-households');
      });

      testWidgets('confirmingTheChoiceRecoversThatAccount', (tester) async {
        await openPage(tester);
        await requestCode(tester, 'anna@example.test');
        accountEmailApi.confirmationToReturn =
            const RecoveryConfirmationChooseAccount([accountWithTwoHouseholds, accountWithoutHousehold]);
        await confirmCode(tester);
        accountEmailApi.confirmationToReturn = const RecoveryConfirmationRebound();
        oidcClient.tokensToReturn = const OidcTokens(accessToken: 'access-recovered');
        identityApi.identityToReturn =
            const CallerIdentity(keycloakUserId: 'sub-recovered', displayName: 'Recovered Person');

        await tester.tap(find.byKey(const Key('recover-by-email-candidate-account-no-household')));
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const Key('recover-by-email-account-confirm-button')));
        await tester.pumpAndSettle();

        expect(accountEmailApi.confirmedRecoveryAccountIds, [null, 'account-no-household']);
        expect(accountEmailApi.confirmedRecoveries.last, ('anna@example.test', '042817'));
        expect(authCubit.state.keycloakUserId, 'sub-recovered');
        expect(find.byType(RecoveryTokenRevealPage), findsOneWidget);
      });

      testWidgets('showsNeutralLabelsForAnEmptyHouseholdNameAndAnEmptyNickname', (tester) async {
        await openPage(tester);
        await requestCode(tester, 'anna@example.test');
        accountEmailApi.confirmationToReturn = const RecoveryConfirmationChooseAccount([
          RecoveryCandidate(accountId: 'account-lagging', households: [
            RecoveryCandidateHousehold(householdName: '', nickname: ''),
          ]),
          accountWithoutHousehold,
        ]);

        await confirmCode(tester);

        expect(find.text('Haushalt ohne Namen (Noch ohne Namen)'), findsOneWidget);
      });
    });
  });
}
