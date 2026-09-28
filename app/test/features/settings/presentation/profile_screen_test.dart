import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/auth/data/account_email_api.dart';
import 'package:sgart/features/auth/data/caller_identity.dart';
import 'package:sgart/features/auth/data/device_credential_store.dart';
import 'package:sgart/features/auth/data/oidc_tokens.dart';
import 'package:sgart/features/auth/presentation/auth_cubit.dart';
import 'package:sgart/features/auth/presentation/recovery_token_reveal_page.dart';
import 'package:sgart/features/households/data/household_summary.dart';
import 'package:sgart/features/members/data/member_view.dart';
import 'package:sgart/features/members/data/members_api.dart';
import 'package:sgart/features/settings/data/nickname_api.dart';
import 'package:sgart/features/settings/presentation/locale_cubit.dart';
import 'package:sgart/features/settings/presentation/locale_settings_page.dart';
import 'package:sgart/features/settings/presentation/profile_screen.dart';
import 'package:sgart/l10n/gen/app_localizations.dart';
import 'package:sgart/shared/errors/app_error.dart';
import 'package:sgart/shared/http/app_exception.dart';
import 'package:sgart/theme/sgart_theme.dart';

import '../../../support/fake_account_email_api.dart';
import '../../../support/fake_auth_dependencies.dart';
import '../../../support/fake_households_dependencies.dart';
import '../../../support/fake_members_dependencies.dart';
import '../../../support/fake_nickname_api.dart';
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
      deviceCredentialStore = FakeDeviceCredentialStore()..tokenToReturn = fakeRecoveryToken;
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

    const activeHousehold = HouseholdSummary(householdId: 'household-1', name: 'Familie Muster');

    Widget buildSubject({
      TextScaler textScaler = TextScaler.noScaling,
      AccountEmailApi? accountEmailApi,
      MembersApi? membersApi,
      NicknameApi? nicknameApi,
      HouseholdSummary household = activeHousehold,
    }) =>
        BlocProvider<AuthCubit>.value(
          value: authCubit,
          child: BlocProvider<LocaleCubit>.value(
            value: localeCubit,
            child: MultiRepositoryProvider(
              providers: [
                RepositoryProvider<DeviceCredentialStore>.value(value: deviceCredentialStore),
                RepositoryProvider<MembersApi>.value(value: membersApi ?? FakeMembersApi()),
                RepositoryProvider<NicknameApi>.value(value: nicknameApi ?? FakeNicknameApi()),
              ],
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
                      child: Scaffold(body: ProfileScreen(activeHousehold: household)),
                    ),
                  ),
                ),
              ),
            ),
          ),
        );

    testWidgets('rendersTheEmailFromTheAuthenticatedAuthCubitButNeverTheJwtDisplayName', (tester) async {
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      // The JWT name is the raw device-credential id for a silently-provisioned account (F3).
      expect(find.text('Anna Testperson'), findsNothing);
      expect(find.text('anna@example.test'), findsOneWidget);
    });

    // Test Manifest: profileScreen_nicknameSection (Story 8.3) — the active household's resolved
    // nickname is shown (fallback header source, section row, and edit affordance).
    group('the nickname section', () {
      testWidgets('showsTheNeutralFallbackInHeaderAndRowWhenTheCallerHasNotSetANicknameYet', (tester) async {
        await tester.pumpWidget(buildSubject());
        await tester.pumpAndSettle();

        expect(find.byKey(const Key('profile-nickname-value')), findsOneWidget);
        expect(find.text('Noch ohne Namen'), findsNWidgets(2)); // the header + the nickname section row
      });

      testWidgets('theSectionLabelNamesTheActiveHouseholdAndUpdatesAfterASwitch', (tester) async {
        // Story 8.7 D1: the label names the active household instead of the generic
        // "diesem Haushalt", so it still makes sense once the switcher chip that used to carry
        // that context is hidden on Profil.
        const otherHousehold = HouseholdSummary(householdId: 'household-2', name: 'WG Küche');

        await tester.pumpWidget(buildSubject());
        await tester.pumpAndSettle();
        expect(find.text('Dein Name in „Familie Muster“'), findsOneWidget);

        await tester.pumpWidget(buildSubject(household: otherHousehold));
        await tester.pumpAndSettle();

        expect(find.text('Dein Name in „Familie Muster“'), findsNothing);
        expect(find.text('Dein Name in „WG Küche“'), findsOneWidget);
      });

      testWidgets('switchingTheActiveHouseholdShowsThatHouseholdsNickname', (tester) async {
        const otherHousehold = HouseholdSummary(householdId: 'household-2', name: 'WG');
        final membersApi = FakeMembersApi()
          ..membersByHousehold = const {
            'household-1': [MemberView(memberId: 'member-1', role: 'ADMIN', isSelf: true, nickname: 'Papa')],
            'household-2': [MemberView(memberId: 'member-2', role: 'ADMIN', isSelf: true, nickname: 'Timo')],
          };
        await tester.pumpWidget(buildSubject(membersApi: membersApi));
        await tester.pumpAndSettle();
        expect(find.text('Papa'), findsNWidgets(2));

        await tester.pumpWidget(buildSubject(membersApi: membersApi, household: otherHousehold));
        await tester.pumpAndSettle();

        expect(find.text('Papa'), findsNothing);
        expect(find.text('Timo'), findsNWidgets(2)); // the header + the nickname section row
      });

      testWidgets('showsTheResolvedNicknameFromTheMemberRosterAndItBecomesTheHeaderSource', (tester) async {
        final membersApi = FakeMembersApi()
          ..membersToReturn = const [
            MemberView(memberId: 'member-1', role: 'ADMIN', isSelf: true, nickname: 'Papa'),
          ];

        await tester.pumpWidget(buildSubject(membersApi: membersApi));
        await tester.pumpAndSettle();

        expect(find.text('Papa'), findsNWidgets(2)); // the header + the nickname section row
      });

      testWidgets('editingTheNicknameCallsTheApiAndUpdatesTheDisplayedValue', (tester) async {
        final nicknameApi = FakeNicknameApi();
        await tester.pumpWidget(buildSubject(nicknameApi: nicknameApi));

        await tester.tap(find.byKey(const Key('profile-nickname-edit-button')));
        await tester.pumpAndSettle();
        await tester.enterText(find.byKey(const Key('profile-nickname-field')), 'Timo');
        await tester.tap(find.byKey(const Key('profile-nickname-save-button')));
        await tester.pumpAndSettle();

        expect(nicknameApi.setCalls, [('household-1', 'Timo')]);
        expect(find.byKey(const Key('profile-nickname-field')), findsNothing); // dialog closed
        expect(find.text('Timo'), findsNWidgets(2)); // the header + the nickname section row
      });

      testWidgets('aRejectedNicknameShowsInlineAndKeepsTheDialogOpen', (tester) async {
        final nicknameApi = FakeNicknameApi()
          ..setNicknameErrorToThrow = const AppException(AppError(code: 'nickname.tooLong', message: 'debug'));
        await tester.pumpWidget(buildSubject(nicknameApi: nicknameApi));

        await tester.tap(find.byKey(const Key('profile-nickname-edit-button')));
        await tester.pumpAndSettle();
        await tester.enterText(find.byKey(const Key('profile-nickname-field')), 'Timo');
        await tester.tap(find.byKey(const Key('profile-nickname-save-button')));
        await tester.pumpAndSettle();

        expect(find.byKey(const Key('profile-nickname-error')), findsOneWidget);
        expect(find.byKey(const Key('profile-nickname-field')), findsOneWidget); // dialog stayed open
      });
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

    // Test Manifest: profileScreen_recoveryTokenRow_opensRevealPage (Story 7.2, AC2, D-C) — the
    // re-view row is present and, on any number of taps, re-opens the shared reveal page.
    testWidgets('theWiederherstellungsschluesselRowOpensTheRecoveryTokenRevealPage', (tester) async {
      await tester.pumpWidget(buildSubject());

      await tester.tap(find.byKey(const Key('profile-recovery-token-row')));
      await tester.pumpAndSettle();

      expect(find.byType(RecoveryTokenRevealPage), findsOneWidget);
      expect(find.byKey(const Key('recovery-token-value')), findsOneWidget);
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
