import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/auth/data/account_email_api.dart';
import 'package:sgart/features/auth/data/caller_identity.dart';
import 'package:sgart/features/auth/data/device_credential_store.dart';
import 'package:sgart/features/auth/data/oidc_tokens.dart';
import 'package:sgart/features/auth/presentation/auth_cubit.dart';
import 'package:sgart/features/auth/presentation/recovery_phrase_reveal_page.dart';
import 'package:sgart/features/settings/presentation/locale_cubit.dart';
import 'package:sgart/features/settings/presentation/locale_settings_page.dart';
import 'package:sgart/features/settings/presentation/profile_screen.dart';
import 'package:sgart/l10n/gen/app_localizations.dart';
import 'package:sgart/theme/sgart_theme.dart';

import '../../../support/fake_account_email_api.dart';
import '../../../support/fake_auth_dependencies.dart';
import '../../../support/fake_households_dependencies.dart';
import '../../../support/fake_settings_dependencies.dart';

void main() {
  group('ProfileScreen', () {
    late FakeOidcClient oidcClient;
    late FakeSecureTokenStorage tokenStorage;
    late FakeIdentityApi identityApi;
    late FakeDeviceCredentialStore deviceCredentialStore;
    late AuthCubit authCubit;
    late LocaleCubit localeCubit;

    setUp(() async {
      oidcClient = FakeOidcClient()..tokensToReturn = const OidcTokens(accessToken: 'access');
      tokenStorage = FakeSecureTokenStorage();
      identityApi = FakeIdentityApi()
        ..identityToReturn = const CallerIdentity(
            keycloakUserId: 'sub-1', displayName: 'Anna Testperson', email: 'anna@example.test');
      deviceCredentialStore = FakeDeviceCredentialStore()..wordsToReturn = List.generate(24, (i) => 'word$i');
      authCubit = AuthCubit(
        oidcClient: oidcClient,
        tokenStorage: tokenStorage,
        identityApi: identityApi,
        deviceCredentialStore: deviceCredentialStore,
        activeHouseholdStore: FakeActiveHouseholdStore(),
      );
      await authCubit.signIn();
      // LocaleCubit sits above MaterialApp in production (main.dart) — provided the same way here
      // so the pushed LocaleSettingsPage reaches it with no re-provide (Story 1.10/1.11).
      localeCubit = LocaleCubit(FakeLocalePreferenceStore());
    });

    tearDown(() async {
      await authCubit.close();
      await localeCubit.close();
    });

    Widget buildSubject({TextScaler textScaler = TextScaler.noScaling, AccountEmailApi? accountEmailApi}) =>
        BlocProvider<AuthCubit>.value(
          value: authCubit,
          child: BlocProvider<LocaleCubit>.value(
            value: localeCubit,
            child: RepositoryProvider<DeviceCredentialStore>.value(
              value: deviceCredentialStore,
              child: _maybeProvideAccountEmailApi(
                accountEmailApi,
                child: MaterialApp(
                  theme: SgartTheme.light(),
                  localizationsDelegates: const [
                    AppLocalizations.delegate,
                    GlobalMaterialLocalizations.delegate,
                    GlobalWidgetsLocalizations.delegate,
                    GlobalCupertinoLocalizations.delegate,
                  ],
                  supportedLocales: AppLocalizations.supportedLocales,
                  home: Builder(
                    builder: (context) => MediaQuery(
                      data: MediaQuery.of(context).copyWith(textScaler: textScaler),
                      child: const Scaffold(body: ProfileScreen()),
                    ),
                  ),
                ),
              ),
            ),
          ),
        );

    testWidgets('rendersTheDisplayNameAndEmailFromTheAuthenticatedAuthCubit', (tester) async {
      await tester.pumpWidget(buildSubject());

      expect(find.text('Anna Testperson'), findsOneWidget);
      expect(find.text('anna@example.test'), findsOneWidget);
    });

    testWidgets('showsTheFixedNotificationsInfoWithNoToggle', (tester) async {
      await tester.pumpWidget(buildSubject());

      expect(find.byKey(const Key('profile-notifications-info')), findsOneWidget);
      expect(find.byType(Switch), findsNothing);
    });

    testWidgets('profilHasNoDataExportOrErasureSurface', (tester) async {
      await tester.pumpWidget(buildSubject());

      expect(find.textContaining('Meine Daten'), findsNothing);
      expect(find.textContaining('Größere Darstellung'), findsNothing);
    });

    // Test Manifest: profileScreen_recoveryPhraseRow_opensRevealPage (Story 7.2, AC2, D-C) — the
    // re-view row is present and, on any number of taps, re-opens the shared reveal page.
    testWidgets('theWiederherstellungsphraseRowOpensTheRecoveryPhraseRevealPage', (tester) async {
      await tester.pumpWidget(buildSubject());

      await tester.tap(find.byKey(const Key('profile-recovery-phrase-row')));
      await tester.pumpAndSettle();

      expect(find.byType(RecoveryPhraseRevealPage), findsOneWidget);
      expect(find.byKey(const Key('recovery-phrase-word-1')), findsOneWidget);
    });

    testWidgets('theSpracheUndRegionRowOpensTheLocaleSettingsPage', (tester) async {
      await tester.pumpWidget(buildSubject());

      await tester.tap(find.byKey(const Key('profile-locale-row')));
      await tester.pumpAndSettle();

      expect(find.byType(LocaleSettingsPage), findsOneWidget);
    });

    testWidgets('interactiveRowsMeetTheFortyEightPixelMinimumTapTarget', (tester) async {
      await tester.pumpWidget(buildSubject());

      expect(tester.getSize(find.byKey(const Key('profile-locale-row'))).height, greaterThanOrEqualTo(48));
    });

    testWidgets('rendersWithoutOverflowAtAnElevatedTextScale', (tester) async {
      await tester.pumpWidget(buildSubject(textScaler: const TextScaler.linear(2.0)));

      expect(tester.takeException(), isNull);
    });

    // Test Manifest: profileRecoveryEmailSection_attachConfirmDetach_updatesState (Story 7.3, AC1).
    group('the E-Mail-Wiederherstellung section', () {
      testWidgets('startsNotAttachedWhenTheAuthCubitCarriesNoEmail', (tester) async {
        identityApi.identityToReturn =
            const CallerIdentity(keycloakUserId: 'sub-1', displayName: 'Anna Testperson', email: '');
        await authCubit.signIn();
        final accountEmailApi = FakeAccountEmailApi();

        await tester.pumpWidget(buildSubject(accountEmailApi: accountEmailApi));

        expect(find.byKey(const Key('profile-recovery-email-add-button')), findsOneWidget);
      });

      testWidgets('attachThenConfirm_movesTheRowToConfirmed', (tester) async {
        identityApi.identityToReturn =
            const CallerIdentity(keycloakUserId: 'sub-1', displayName: 'Anna Testperson', email: '');
        await authCubit.signIn();
        final accountEmailApi = FakeAccountEmailApi();

        await tester.pumpWidget(buildSubject(accountEmailApi: accountEmailApi));
        await tester.tap(find.byKey(const Key('profile-recovery-email-add-button')));
        await tester.pumpAndSettle();

        await tester.enterText(find.byKey(const Key('add-recovery-email-field')), 'anna@example.test');
        await tester.tap(find.byKey(const Key('add-recovery-email-submit-button')));
        await tester.pumpAndSettle();

        expect(accountEmailApi.attachedEmails, ['anna@example.test']);
        await tester.enterText(find.byKey(const Key('confirm-email-code-field')), '042817');
        await tester.tap(find.byKey(const Key('confirm-email-code-submit-button')));
        await tester.pumpAndSettle();

        expect(accountEmailApi.confirmedCodes, ['042817']);
        // Both pushed pages popped, landing back on Profil with the confirmed state visible.
        expect(find.byKey(const Key('add-recovery-email-field')), findsNothing);
        expect(find.text('Bestätigt'), findsOneWidget);
        expect(find.byKey(const Key('profile-recovery-email-detach-button')), findsOneWidget);
      });

      testWidgets('seedsPendingConfirmationWhenTheAuthCubitEmailIsUnverified', (tester) async {
        // Story 7.3 review finding: an attached-but-unconfirmed email must not read "Bestätigt" on
        // relaunch (only a Keycloak-confirmed `emailVerified` earns that label).
        identityApi.identityToReturn = const CallerIdentity(
            keycloakUserId: 'sub-1',
            displayName: 'Anna Testperson',
            email: 'anna@example.test',
            emailVerified: false);
        await authCubit.signIn();
        final accountEmailApi = FakeAccountEmailApi();

        await tester.pumpWidget(buildSubject(accountEmailApi: accountEmailApi));

        expect(find.text('Bestätigung ausstehend'), findsOneWidget);
        expect(find.text('Bestätigt'), findsNothing);
      });

      testWidgets('seedsConfirmedWhenTheAuthCubitEmailIsVerified', (tester) async {
        identityApi.identityToReturn = const CallerIdentity(
            keycloakUserId: 'sub-1',
            displayName: 'Anna Testperson',
            email: 'anna@example.test',
            emailVerified: true);
        await authCubit.signIn();
        final accountEmailApi = FakeAccountEmailApi();

        await tester.pumpWidget(buildSubject(accountEmailApi: accountEmailApi));

        expect(find.text('Bestätigt'), findsOneWidget);
      });

      testWidgets('detach_returnsToNotAttachedAfterConfirmation', (tester) async {
        identityApi.identityToReturn = const CallerIdentity(
            keycloakUserId: 'sub-1',
            displayName: 'Anna Testperson',
            email: 'anna@example.test',
            emailVerified: true);
        await authCubit.signIn();
        final accountEmailApi = FakeAccountEmailApi();

        await tester.pumpWidget(buildSubject(accountEmailApi: accountEmailApi));
        expect(find.byKey(const Key('profile-recovery-email-detach-button')), findsOneWidget);

        await tester.tap(find.byKey(const Key('profile-recovery-email-detach-button')));
        await tester.pumpAndSettle();
        await tester.tap(find.byKey(const Key('profile-recovery-email-detach-confirm-button')));
        await tester.pumpAndSettle();

        expect(accountEmailApi.detachCallCount, 1);
        expect(find.byKey(const Key('profile-recovery-email-add-button')), findsOneWidget);
      });
    });
  });
}

// No AccountEmailApi ancestor when a test doesn't care about the recovery-email section —
// ProfileScreen's own guarded resolver then falls through to its inert stand-in.
Widget _maybeProvideAccountEmailApi(AccountEmailApi? accountEmailApi, {required Widget child}) {
  if (accountEmailApi == null) return child;
  return RepositoryProvider<AccountEmailApi>.value(value: accountEmailApi, child: child);
}
