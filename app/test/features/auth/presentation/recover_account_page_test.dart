import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/auth/data/caller_identity.dart';
import 'package:sgart/features/auth/data/oidc_tokens.dart';
import 'package:sgart/features/auth/data/recovery_phrase.dart';
import 'package:sgart/features/auth/presentation/auth_cubit.dart';
import 'package:sgart/features/auth/presentation/auth_state.dart';
import 'package:sgart/features/auth/presentation/recover_account_page.dart';

import '../../../support/fake_auth_dependencies.dart';
import '../../../support/fake_households_dependencies.dart';
import '../../../support/widget_test_harness.dart';

void main() {
  group('RecoverAccountPage', () {
    late FakeOidcClient oidcClient;
    late FakeSecureTokenStorage tokenStorage;
    late FakeIdentityApi identityApi;
    late FakeDeviceCredentialStore deviceCredentialStore;
    late FakeActiveHouseholdStore activeHouseholdStore;
    late AuthCubit authCubit;
    late List<String> recoveryWords;

    setUp(() async {
      recoveryWords = RecoveryPhrase.wordsFromEntropy(Uint8List(32)); // fixed synthetic test vector
      oidcClient = FakeOidcClient()
        ..tokensToReturn = const OidcTokens(accessToken: 'access-throwaway');
      tokenStorage = FakeSecureTokenStorage();
      identityApi = FakeIdentityApi()
        ..identityToReturn = const CallerIdentity(
            keycloakUserId: 'sub-throwaway', displayName: 'Throwaway', email: 'throwaway@example.test');
      deviceCredentialStore = FakeDeviceCredentialStore();
      activeHouseholdStore = FakeActiveHouseholdStore()..activeId = 'throwaway-household';
      authCubit = AuthCubit(
        oidcClient: oidcClient,
        tokenStorage: tokenStorage,
        identityApi: identityApi,
        deviceCredentialStore: deviceCredentialStore,
        activeHouseholdStore: activeHouseholdStore,
      );
      // Mirrors production: a person only reaches the choice screen's recovery action already
      // signed in as the throwaway account (Story 7.1).
      await authCubit.signIn();
    });

    tearDown(() => authCubit.close());

    // Mirrors CreateOrAwaitChoicePage._openRecoverAccount: AuthCubit is re-provided at the push
    // site, since the root Navigator sits above the "open" screen's own ancestor providers and a
    // pushed route would otherwise miss them entirely (the provider-escape lesson, Story 1.6).
    Widget buildSubject() => wrapForTesting(
          BlocProvider<AuthCubit>.value(
            value: authCubit,
            child: Builder(
              builder: (context) => Scaffold(
                body: Center(
                  child: TextButton(
                    key: const Key('open-recover-account'),
                    onPressed: () => Navigator.of(context).push(
                      MaterialPageRoute<void>(
                        builder: (_) => BlocProvider<AuthCubit>.value(
                          value: authCubit,
                          child: const RecoverAccountPage(),
                        ),
                      ),
                    ),
                    child: const Text('open'),
                  ),
                ),
              ),
            ),
          ),
        );

    Future<void> openRecoverAccountPage(WidgetTester tester) async {
      await tester.pumpWidget(buildSubject());
      await tester.tap(find.byKey(const Key('open-recover-account')));
      await tester.pumpAndSettle();
    }

    testWidgets('recoverAccountPage_invalidPhrase_showsInlineError', (tester) async {
      deviceCredentialStore.restoreErrorToThrow = const InvalidRecoveryPhrase();
      await openRecoverAccountPage(tester);

      await tester.enterText(find.byKey(const Key('recover-account-phrase-field')), 'not a valid phrase');
      await tester.tap(find.byKey(const Key('recover-account-submit-button')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('recover-account-error')), findsOneWidget);
      // Rejected fast, changes nothing (AC3): the page stays open, and the underlying throwaway
      // session is left fully intact — the global auth state is still `authenticated` (no
      // inProgress/failure emit tore it down), and the stored tokens are untouched.
      expect(find.byType(RecoverAccountPage), findsOneWidget);
      expect(authCubit.state.status, AuthStatus.authenticated);
      expect(tokenStorage.storedTokens!.accessToken, 'access-throwaway');
      expect(activeHouseholdStore.cleared, isFalse);
    });

    testWidgets('recoverAccountPage_signInFailsAfterValidPhrase_showsGenericErrorAndStaysOpen', (tester) async {
      await openRecoverAccountPage(tester);

      // A valid phrase imports fine, but the Direct-Grant exchange then fails (e.g. server
      // unreachable). The page must surface that instead of silently resetting the form.
      oidcClient.signInErrorToThrow = Exception('server unreachable');

      await tester.enterText(find.byKey(const Key('recover-account-phrase-field')), recoveryWords.join(' '));
      await tester.tap(find.byKey(const Key('recover-account-submit-button')));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('recover-account-signin-error')), findsOneWidget);
      expect(find.byType(RecoverAccountPage), findsOneWidget);
      expect(authCubit.state.status, AuthStatus.failure);
      expect(deviceCredentialStore.lastRestoredWords, recoveryWords); // the import did happen
    });

    testWidgets('recoverAccountPage_validPhrase_swapsIdentityAndReroutes', (tester) async {
      await openRecoverAccountPage(tester);

      // The next signIn() (driven by recoverFromPhrase) resolves to the *existing* account behind
      // the phrase — a different identity than the throwaway one this cubit started as.
      oidcClient.tokensToReturn = const OidcTokens(accessToken: 'access-recovered');
      identityApi.identityToReturn = const CallerIdentity(
          keycloakUserId: 'sub-recovered', displayName: 'Recovered Person', email: 'recovered@example.test');

      await tester.enterText(find.byKey(const Key('recover-account-phrase-field')), recoveryWords.join(' '));
      await tester.tap(find.byKey(const Key('recover-account-submit-button')));
      await tester.pumpAndSettle();

      // Pops itself once the swap lands, letting the AuthGate subtree underneath show the
      // recovered identity's households (here: the placeholder screen behind the pushed route).
      expect(find.byType(RecoverAccountPage), findsNothing);
      expect(authCubit.state.status, AuthStatus.authenticated);
      expect(authCubit.state.keycloakUserId, 'sub-recovered');
      expect(deviceCredentialStore.lastRestoredWords, recoveryWords);
      expect(activeHouseholdStore.cleared, isTrue);
    });
  });
}
