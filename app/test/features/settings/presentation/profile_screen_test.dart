import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/auth/data/caller_identity.dart';
import 'package:sgart/features/auth/data/oidc_tokens.dart';
import 'package:sgart/features/auth/presentation/auth_cubit.dart';
import 'package:sgart/features/auth/presentation/auth_state.dart';
import 'package:sgart/features/settings/presentation/locale_cubit.dart';
import 'package:sgart/features/settings/presentation/locale_settings_page.dart';
import 'package:sgart/features/settings/presentation/profile_screen.dart';
import 'package:sgart/l10n/gen/app_localizations.dart';
import 'package:sgart/theme/sgart_theme.dart';

import '../../../support/fake_auth_dependencies.dart';
import '../../../support/fake_households_dependencies.dart';
import '../../../support/fake_settings_dependencies.dart';

void main() {
  group('ProfileScreen', () {
    late FakeOidcClient oidcClient;
    late FakeSecureTokenStorage tokenStorage;
    late FakeIdentityApi identityApi;
    late AuthCubit authCubit;
    late LocaleCubit localeCubit;

    setUp(() async {
      oidcClient = FakeOidcClient()..tokensToReturn = const OidcTokens(accessToken: 'access');
      tokenStorage = FakeSecureTokenStorage();
      identityApi = FakeIdentityApi()
        ..identityToReturn = const CallerIdentity(
            keycloakUserId: 'sub-1', displayName: 'Anna Testperson', email: 'anna@example.test');
      authCubit = AuthCubit(
        oidcClient: oidcClient,
        tokenStorage: tokenStorage,
        identityApi: identityApi,
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

    Widget buildSubject({TextScaler textScaler = TextScaler.noScaling}) => BlocProvider<AuthCubit>.value(
          value: authCubit,
          child: BlocProvider<LocaleCubit>.value(
            value: localeCubit,
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

    testWidgets('theSpracheUndRegionRowOpensTheLocaleSettingsPage', (tester) async {
      await tester.pumpWidget(buildSubject());

      await tester.tap(find.byKey(const Key('profile-locale-row')));
      await tester.pumpAndSettle();

      expect(find.byType(LocaleSettingsPage), findsOneWidget);
    });

    testWidgets('signingOutFromProfilClearsTheSession', (tester) async {
      await tester.pumpWidget(buildSubject());

      await tester.tap(find.byKey(const Key('sign-out-button')));
      await tester.pumpAndSettle();

      expect(tokenStorage.cleared, isTrue);
      expect(authCubit.state.status, AuthStatus.unauthenticated);
    });

    testWidgets('interactiveRowsMeetTheFortyEightPixelMinimumTapTarget', (tester) async {
      await tester.pumpWidget(buildSubject());

      expect(tester.getSize(find.byKey(const Key('profile-locale-row'))).height, greaterThanOrEqualTo(48));
      expect(tester.getSize(find.byKey(const Key('sign-out-button'))).height, greaterThanOrEqualTo(48));
    });

    testWidgets('rendersWithoutOverflowAtAnElevatedTextScale', (tester) async {
      await tester.pumpWidget(buildSubject(textScaler: const TextScaler.linear(2.0)));

      expect(tester.takeException(), isNull);
    });
  });
}
