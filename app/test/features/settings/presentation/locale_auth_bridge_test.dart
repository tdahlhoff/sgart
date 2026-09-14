import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/auth/data/caller_identity.dart';
import 'package:sgart/features/auth/data/oidc_tokens.dart';
import 'package:sgart/features/auth/presentation/auth_cubit.dart';
import 'package:sgart/features/auth/presentation/auth_state.dart';
import 'package:sgart/features/settings/presentation/locale_auth_bridge.dart';
import 'package:sgart/features/settings/presentation/locale_cubit.dart';
import 'package:sgart/features/settings/presentation/locale_state.dart';

import '../../../support/fake_auth_dependencies.dart';
import '../../../support/fake_households_dependencies.dart';
import '../../../support/fake_settings_dependencies.dart';

void main() {
  group('LocaleAuthBridge', () {
    late FakeOidcClient oidcClient;
    late FakeSecureTokenStorage tokenStorage;
    late FakeIdentityApi identityApi;
    late FakeLocalePreferenceStore localeStore;
    late LocaleCubit localeCubit;

    setUp(() {
      oidcClient = FakeOidcClient();
      tokenStorage = FakeSecureTokenStorage();
      identityApi = FakeIdentityApi();
      localeStore = FakeLocalePreferenceStore();
      localeCubit = LocaleCubit(localeStore);
    });

    AuthCubit buildAuthCubit() => AuthCubit(
          oidcClient: oidcClient,
          tokenStorage: tokenStorage,
          identityApi: identityApi,
          deviceCredentialStore: FakeDeviceCredentialStore(),
          activeHouseholdStore: FakeActiveHouseholdStore(),
        );

    // LocaleCubit provided above MaterialApp (as in the app root); the bridge sits below, in the
    // AuthCubit subtree, and reaches the ancestor LocaleCubit — the real provider-tree shape.
    Widget build(AuthCubit authCubit) => BlocProvider<LocaleCubit>.value(
          value: localeCubit,
          child: MaterialApp(
            home: BlocProvider<AuthCubit>.value(
              value: authCubit,
              child: const LocaleAuthBridge(child: SizedBox()),
            ),
          ),
        );

    testWidgets('appliesTheUsersStoredLocaleOnSignIn', (tester) async {
      localeStore.seed('sub-1', 'de-CH');
      oidcClient.tokensToReturn = const OidcTokens(accessToken: 'access');
      identityApi.identityToReturn =
          const CallerIdentity(keycloakUserId: 'sub-1', displayName: 'Anna', email: 'anna@example.test');
      final authCubit = buildAuthCubit();
      addTearDown(authCubit.close);
      await tester.pumpWidget(build(authCubit));

      await authCubit.signIn();
      await tester.pumpAndSettle();

      expect(localeCubit.state, const ExplicitLocale(Locale('de', 'CH')));
    });

    testWidgets('reAppliesTheLocaleWhenAnotherUserSignsInWithoutAnInterveningSignOut', (tester) async {
      localeStore.seed('sub-1', 'de-CH');
      localeStore.seed('sub-2', 'de-AT');
      final authCubit = _ControllableAuthCubit(
        oidcClient: oidcClient,
        tokenStorage: tokenStorage,
        identityApi: identityApi,
        deviceCredentialStore: FakeDeviceCredentialStore(),
        activeHouseholdStore: FakeActiveHouseholdStore(),
      );
      addTearDown(authCubit.close);
      await tester.pumpWidget(build(authCubit));

      authCubit.emitState(const AuthState.authenticated('Anna', 'sub-1', 'anna@example.test'));
      await tester.pumpAndSettle();
      expect(localeCubit.state, const ExplicitLocale(Locale('de', 'CH')));

      // A second member becomes authenticated with no unauthenticated step in between — same status,
      // different sub. Their locale must be applied, never inherited from the previous person.
      authCubit.emitState(const AuthState.authenticated('Bob', 'sub-2', 'bob@example.test'));
      await tester.pumpAndSettle();
      expect(localeCubit.state, const ExplicitLocale(Locale('de', 'AT')));
    });

    // AuthCubit no longer has a sign-out action (Story 7.1 code review — the device credential is
    // permanent, so there is no working "signed out" state to return to); this drives the bridge's
    // own reaction to an `unauthenticated` transition directly via `_ControllableAuthCubit`, the way
    // `reAppliesTheLocaleWhenAnotherUserSignsInWithoutAnInterveningSignOut` already does above.
    testWidgets('resetsToDeviceDefaultAndClearsTheStoredLocaleWhenAuthGoesUnauthenticated', (tester) async {
      localeStore.seed('sub-1', 'de-CH');
      final authCubit = _ControllableAuthCubit(
        oidcClient: oidcClient,
        tokenStorage: tokenStorage,
        identityApi: identityApi,
        deviceCredentialStore: FakeDeviceCredentialStore(),
        activeHouseholdStore: FakeActiveHouseholdStore(),
      );
      addTearDown(authCubit.close);
      await tester.pumpWidget(build(authCubit));

      authCubit.emitState(const AuthState.authenticated('Anna', 'sub-1', 'anna@example.test'));
      await tester.pumpAndSettle();
      expect(localeCubit.state, const ExplicitLocale(Locale('de', 'CH')));

      authCubit.emitState(const AuthState.unauthenticated());
      await tester.pumpAndSettle();

      expect(localeCubit.state, const SystemLocale());
      expect(localeStore.clearedUsers, contains('sub-1'));
      expect(localeStore.storedTagFor('sub-1'), isNull);
    });
  });
}

/// Lets a test drive arbitrary [AuthState]s (e.g. two `authenticated` states with different `sub`s)
/// to exercise the bridge's `listenWhen` without wiring a full sign-in/sign-out flow.
class _ControllableAuthCubit extends AuthCubit {
  _ControllableAuthCubit({
    required super.oidcClient,
    required super.tokenStorage,
    required super.identityApi,
    required super.deviceCredentialStore,
    required super.activeHouseholdStore,
  });

  void emitState(AuthState state) => emit(state);
}
