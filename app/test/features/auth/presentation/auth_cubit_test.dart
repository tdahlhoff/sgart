import 'dart:async';
import 'dart:typed_data';

import 'package:bloc_test/bloc_test.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/auth/data/caller_identity.dart';
import 'package:sgart/features/auth/data/oidc_tokens.dart';
import 'package:sgart/features/auth/data/recovery_token.dart';
import 'package:sgart/features/auth/presentation/auth_cubit.dart';
import 'package:sgart/features/auth/presentation/auth_state.dart';
import 'package:sgart/shared/errors/app_error.dart';
import 'package:sgart/shared/http/app_exception.dart';

import '../../../support/fake_auth_dependencies.dart';
import '../../../support/fake_households_dependencies.dart';
import '../../../support/fake_push_notifications.dart';

void main() {
  group('AuthState', () {
    test('authenticatedCarriesTheDisplayNameAndKeycloakUserId', () {
      const state = AuthState.authenticated('Anna Testperson', 'sub-1');

      expect(state.displayName, 'Anna Testperson');
      expect(state.keycloakUserId, 'sub-1');
    });

    test('equalityAndHashCodeIncludeTheKeycloakUserId', () {
      const first = AuthState.authenticated('Anna', 'sub-1');
      const same = AuthState.authenticated('Anna', 'sub-1');
      const differentAccount = AuthState.authenticated('Anna', 'sub-2');

      expect(first, same);
      expect(first.hashCode, same.hashCode);
      expect(first, isNot(differentAccount));
    });
  });

  group('AuthCubit', () {
    late FakeOidcClient oidcClient;
    late FakeSecureTokenStorage tokenStorage;
    late FakeIdentityApi identityApi;
    late FakeDeviceCredentialStore deviceCredentialStore;
    late FakeActiveHouseholdStore activeHouseholdStore;

    setUp(() {
      oidcClient = FakeOidcClient();
      tokenStorage = FakeSecureTokenStorage();
      identityApi = FakeIdentityApi();
      deviceCredentialStore = FakeDeviceCredentialStore();
      activeHouseholdStore = FakeActiveHouseholdStore();
    });

    AuthCubit buildCubit() => AuthCubit(
          oidcClient: oidcClient,
          tokenStorage: tokenStorage,
          identityApi: identityApi,
          deviceCredentialStore: deviceCredentialStore,
          activeHouseholdStore: activeHouseholdStore,
        );

    test('startsUnauthenticated', () {
      expect(buildCubit().state, const AuthState.unauthenticated());
      buildCubit().close();
    });

    blocTest<AuthCubit, AuthState>(
      'signIn_authenticatesAndStoresTheTokensOnSuccess',
      build: () {
        oidcClient.tokensToReturn = const OidcTokens(accessToken: 'access', refreshToken: 'refresh');
        identityApi.identityToReturn = const CallerIdentity(
            keycloakUserId: 'sub-1', displayName: 'Anna Testperson');
        return buildCubit();
      },
      act: (cubit) => cubit.signIn(),
      expect: () => [
        const AuthState.inProgress(),
        const AuthState.authenticated('Anna Testperson', 'sub-1'),
      ],
      verify: (_) => expect(tokenStorage.storedTokens!.accessToken, 'access'),
    );

    blocTest<AuthCubit, AuthState>(
      'signIn_emitsAFailureAndClearsStoredTokensWhenTheIdentityCallFails',
      build: () {
        oidcClient.tokensToReturn = const OidcTokens(accessToken: 'access');
        identityApi.errorToThrow = const AppException(AppError(code: 'identity.notAMember', message: 'debug'));
        return buildCubit();
      },
      act: (cubit) => cubit.signIn(),
      expect: () => [
        const AuthState.inProgress(),
        const AuthState.failure(AppError(code: 'identity.notAMember', message: 'debug')),
      ],
      verify: (_) => expect(tokenStorage.cleared, isTrue),
    );

    blocTest<AuthCubit, AuthState>(
      'bootstrap_resumesAnAuthenticatedSessionWhenTokensAreAlreadyStored',
      build: () {
        tokenStorage.storedTokens = const OidcTokens(accessToken: 'access');
        identityApi.identityToReturn =
            const CallerIdentity(keycloakUserId: 'sub-1', displayName: 'Anna');
        return buildCubit();
      },
      act: (cubit) => cubit.bootstrap(),
      expect: () => [const AuthState.authenticated('Anna', 'sub-1')],
    );

    // Test Manifest: firstLaunch_provisionsAndSignsInWithNoBrowserSurface (Story 7.1, AC1) — on a
    // fresh install (no stored tokens), bootstrap() silently drives OidcClient.signIn() (which, in
    // production, provisions the device account and signs in via the Direct-Grant flow) straight
    // to the authenticated state, with no separate user-initiated step and no browser-shaped API
    // in [OidcClient]'s signature at all.
    blocTest<AuthCubit, AuthState>(
      'bootstrap_silentlyProvisionsAndSignsInWhenNoTokensAreStored',
      build: () {
        oidcClient.tokensToReturn = const OidcTokens(accessToken: 'access');
        identityApi.identityToReturn = const CallerIdentity(
            keycloakUserId: 'sub-1', displayName: 'Anna Testperson');
        return buildCubit();
      },
      act: (cubit) => cubit.bootstrap(),
      expect: () => [
        const AuthState.inProgress(),
        const AuthState.authenticated('Anna Testperson', 'sub-1'),
      ],
      verify: (_) => expect(tokenStorage.storedTokens!.accessToken, 'access'),
    );

    blocTest<AuthCubit, AuthState>(
      'bootstrap_keepsStoredTokensWhenTheIdentityCallFailsTransiently',
      build: () {
        tokenStorage.storedTokens = const OidcTokens(accessToken: 'access', refreshToken: 'refresh');
        identityApi.errorToThrow =
            const AppException(AppError(code: 'network.unreachable', message: 'connection refused'));
        return buildCubit();
      },
      act: (cubit) => cubit.bootstrap(),
      expect: () => [const AuthState.failure(AppError(code: 'network.unreachable', message: 'connection refused'))],
      verify: (_) {
        expect(tokenStorage.cleared, isFalse);
        expect(tokenStorage.storedTokens, isNotNull);
      },
    );

    // The transparent retry-once-on-401 behavior moved to `AuthenticatedHttpClient` itself (every
    // authenticated call now benefits, not just `/me`) — see authenticated_http_client_test.dart
    // for that coverage. `_loadCallerIdentity` now only reacts to the final outcome.
    blocTest<AuthCubit, AuthState>(
      'bootstrap_clearsTheSessionWhenTheIdentityCallIsRejectedAsUnauthorized',
      build: () {
        tokenStorage.storedTokens = const OidcTokens(accessToken: 'expired', refreshToken: 'refresh');
        identityApi.errorToThrow = const AppException(AppError(code: 'auth.unauthorized', message: 'expired'));
        return buildCubit();
      },
      act: (cubit) => cubit.bootstrap(),
      expect: () => [const AuthState.failure(AppError(code: 'auth.unauthorized', message: 'expired'))],
      verify: (_) => expect(tokenStorage.cleared, isTrue),
    );

    group('tryRefreshTokens (public — wired into AuthenticatedHttpClient as its refresh callback)', () {
      test('exchangesTheStoredRefreshTokenForAFreshAccessTokenAndReturnsTrue', () async {
        oidcClient.tokensToReturn = const OidcTokens(accessToken: 'access', refreshToken: 'refresh');
        identityApi.identityToReturn =
            const CallerIdentity(keycloakUserId: 'sub-1', displayName: 'Anna');
        oidcClient.refreshedTokensToReturn = const OidcTokens(accessToken: 'fresh', refreshToken: 'rotated');
        final cubit = buildCubit();
        await cubit.signIn();

        final refreshed = await cubit.tryRefreshTokens();

        expect(refreshed, isTrue);
        expect(oidcClient.lastRefreshToken, 'refresh');
        expect(tokenStorage.storedTokens!.accessToken, 'fresh');
        await cubit.close();
      });

      test('returnsFalseWhenNoRefreshTokenIsStored', () async {
        oidcClient.tokensToReturn = const OidcTokens(accessToken: 'access');
        identityApi.identityToReturn =
            const CallerIdentity(keycloakUserId: 'sub-1', displayName: 'Anna');
        final cubit = buildCubit();
        await cubit.signIn();

        final refreshed = await cubit.tryRefreshTokens();

        expect(refreshed, isFalse);
        await cubit.close();
      });

      test('returnsFalseWhenTheRefreshCallFails', () async {
        oidcClient.tokensToReturn = const OidcTokens(accessToken: 'access', refreshToken: 'refresh');
        identityApi.identityToReturn =
            const CallerIdentity(keycloakUserId: 'sub-1', displayName: 'Anna');
        oidcClient.refreshErrorToThrow = StateError('refresh token revoked');
        final cubit = buildCubit();
        await cubit.signIn();

        final refreshed = await cubit.tryRefreshTokens();

        expect(refreshed, isFalse);
        await cubit.close();
      });
    });

    group('tryReauthenticate (Story 8.2 — wired into AuthenticatedHttpClient as its refresh callback)', () {
      test('tryReauthenticate_returnsTrueViaRefreshWhenTheRefreshTokenIsValid', () async {
        oidcClient.tokensToReturn = const OidcTokens(accessToken: 'access', refreshToken: 'refresh');
        identityApi.identityToReturn =
            const CallerIdentity(keycloakUserId: 'sub-1', displayName: 'Anna');
        oidcClient.refreshedTokensToReturn = const OidcTokens(accessToken: 'fresh', refreshToken: 'rotated');
        final cubit = buildCubit();
        await cubit.signIn();

        final result = await cubit.tryReauthenticate();

        expect(result, isTrue);
        expect(oidcClient.refreshCallCount, 1);
        expect(oidcClient.signInCallCount, 1); // only the initial signIn() above — no device fallback
        expect(cubit.currentAccessToken, 'fresh');
        await cubit.close();
      });

      test(
          'tryReauthenticate_silentlyReAuthenticatesViaTheDeviceCredentialWhenTheRefreshTokenIsDead',
          () async {
        oidcClient.tokensToReturn = const OidcTokens(accessToken: 'access', refreshToken: 'refresh');
        identityApi.identityToReturn =
            const CallerIdentity(keycloakUserId: 'sub-1', displayName: 'Anna');
        final cubit = buildCubit();
        await cubit.signIn();
        oidcClient.refreshErrorToThrow = StateError('refresh token dead');
        // The device sign-in fallback re-derives the same identity with fresh tokens.
        oidcClient.tokensToReturn = const OidcTokens(accessToken: 'device-fresh', refreshToken: 'device-refresh');
        final emittedStates = <AuthState>[];
        final subscription = cubit.stream.listen(emittedStates.add);

        final result = await cubit.tryReauthenticate();

        expect(result, isTrue);
        expect(cubit.currentAccessToken, 'device-fresh');
        expect(tokenStorage.storedTokens!.accessToken, 'device-fresh');
        // The whole point: the session stays authenticated with no state churn — no inProgress,
        // no unauthenticated flash, nothing for the UI to react to.
        expect(emittedStates, isEmpty);
        await subscription.cancel();
        await cubit.close();
      });

      test('tryReauthenticate_returnsFalseWhenBothTheRefreshAndTheDeviceSignInFail', () async {
        oidcClient.tokensToReturn = const OidcTokens(accessToken: 'access', refreshToken: 'refresh');
        identityApi.identityToReturn =
            const CallerIdentity(keycloakUserId: 'sub-1', displayName: 'Anna');
        final cubit = buildCubit();
        await cubit.signIn();
        oidcClient.refreshErrorToThrow = StateError('refresh token dead');
        oidcClient.signInErrorToThrow = StateError('device credential gone');
        final emittedStates = <AuthState>[];
        final subscription = cubit.stream.listen(emittedStates.add);

        final result = await cubit.tryReauthenticate();

        expect(result, isFalse);
        expect(emittedStates, isEmpty); // tryReauthenticate itself never emits, even on failure
        await subscription.cancel();
        await cubit.close();
      });

      test('tryReauthenticate_sharesASingleInFlightAttemptForConcurrentCallers', () async {
        oidcClient.tokensToReturn = const OidcTokens(accessToken: 'access', refreshToken: 'refresh');
        identityApi.identityToReturn =
            const CallerIdentity(keycloakUserId: 'sub-1', displayName: 'Anna');
        final cubit = buildCubit();
        await cubit.signIn();
        oidcClient.refreshedTokensToReturn = const OidcTokens(accessToken: 'fresh', refreshToken: 'rotated');
        oidcClient.refreshGate = Completer<void>();

        final first = cubit.tryReauthenticate();
        final second = cubit.tryReauthenticate();
        expect(oidcClient.refreshCallCount, 1); // the second caller never started its own refresh
        oidcClient.refreshGate!.complete();
        final results = await Future.wait([first, second]);

        expect(results, [isTrue, isTrue]);
        expect(oidcClient.refreshCallCount, 1);

        // A later, separate attempt starts fresh (not mistaken for the completed one).
        oidcClient.refreshedTokensToReturn = const OidcTokens(accessToken: 'fresh-2', refreshToken: 'rotated-2');
        final third = await cubit.tryReauthenticate();
        expect(third, isTrue);
        expect(oidcClient.refreshCallCount, 2);
        await cubit.close();
      });

      test(
          'tryReauthenticate_sharesASingleInFlightAttemptForConcurrentCallersDuringDeviceReAuth',
          () async {
        oidcClient.tokensToReturn = const OidcTokens(accessToken: 'access', refreshToken: 'refresh');
        identityApi.identityToReturn =
            const CallerIdentity(keycloakUserId: 'sub-1', displayName: 'Anna');
        final cubit = buildCubit();
        await cubit.signIn();
        // The refresh token is dead, so both concurrent callers must fall through to the device
        // sign-in fallback — and still share one in-flight attempt there, not just on the refresh
        // fast path.
        oidcClient.refreshErrorToThrow = StateError('refresh token dead');
        oidcClient.tokensToReturn = const OidcTokens(accessToken: 'device-fresh', refreshToken: 'device-refresh');
        oidcClient.signInGate = Completer<void>();
        final signInCallCountBeforeReauth = oidcClient.signInCallCount; // the initial cubit.signIn() above

        final first = cubit.tryReauthenticate();
        final second = cubit.tryReauthenticate();
        await Future<void>.delayed(Duration.zero); // let both callers reach the gated signIn() call
        // The second caller never started its own device sign-in — only one new call beyond setup.
        expect(oidcClient.signInCallCount, signInCallCountBeforeReauth + 1);
        oidcClient.signInGate!.complete();
        final results = await Future.wait([first, second]);

        expect(results, [isTrue, isTrue]);
        expect(oidcClient.signInCallCount, signInCallCountBeforeReauth + 1);
        expect(cubit.currentAccessToken, 'device-fresh');
        await cubit.close();
      });
    });

    test('doesNotEmitAfterTheCubitIsClosedMidSignIn', () async {
      oidcClient.tokensToReturn = const OidcTokens(accessToken: 'access');
      identityApi.identityToReturn =
          const CallerIdentity(keycloakUserId: 'sub-1', displayName: 'Anna');
      final cubit = buildCubit();

      final signInFuture = cubit.signIn();
      await cubit.close();

      await signInFuture;
    });

    group('push notification registration (Story 4.5, AC5)', () {
      late FakePushNotifications pushNotifications;

      setUp(() {
        pushNotifications = FakePushNotifications();
      });

      AuthCubit buildCubitWithPush() => AuthCubit(
            oidcClient: oidcClient,
            tokenStorage: tokenStorage,
            identityApi: identityApi,
            deviceCredentialStore: deviceCredentialStore,
            activeHouseholdStore: activeHouseholdStore,
            pushNotifications: pushNotifications,
          );

      test('signIn_registersTheDeviceTokenOnceAuthenticated', () async {
        oidcClient.tokensToReturn = const OidcTokens(accessToken: 'access');
        identityApi.identityToReturn =
            const CallerIdentity(keycloakUserId: 'sub-1', displayName: 'Anna');
        final cubit = buildCubitWithPush();

        await cubit.signIn();
        await Future<void>.delayed(Duration.zero); // the registration call is fire-and-forget

        expect(pushNotifications.registerCallCount, 1);
        await cubit.close();
      });

      test('signIn_neverRegistersWhenAuthenticationFails', () async {
        oidcClient.tokensToReturn = const OidcTokens(accessToken: 'access');
        identityApi.errorToThrow = const AppException(AppError(code: 'identity.notAMember', message: 'debug'));
        final cubit = buildCubitWithPush();

        await cubit.signIn();
        await Future<void>.delayed(Duration.zero);

        expect(pushNotifications.registerCallCount, 0);
        await cubit.close();
      });

      test('worksWithNoPushNotificationsDependencyAtAll', () async {
        oidcClient.tokensToReturn = const OidcTokens(accessToken: 'access');
        identityApi.identityToReturn =
            const CallerIdentity(keycloakUserId: 'sub-1', displayName: 'Anna');
        final cubit = buildCubit(); // no pushNotifications injected

        await cubit.signIn();

        await cubit.close();
      });
    });

    group('recoverFromToken (Story 7.2, AC3, D-E; token format Story 8.5)', () {
      late String recoveryToken;

      setUp(() async {
        recoveryToken = await RecoveryToken.format(Uint8List(16));
      });

      blocTest<AuthCubit, AuthState>(
        'recoverFromToken_withValidToken_signsInAndClearsActiveHousehold',
        build: () {
          activeHouseholdStore.activeId = 'throwaway-household';
          oidcClient.tokensToReturn = const OidcTokens(accessToken: 'access');
          identityApi.identityToReturn = const CallerIdentity(
              keycloakUserId: 'sub-recovered', displayName: 'Anna Recovered');
          return buildCubit();
        },
        act: (cubit) => cubit.recoverFromToken(recoveryToken),
        expect: () => [
          const AuthState.inProgress(),
          const AuthState.authenticated('Anna Recovered', 'sub-recovered'),
        ],
        verify: (_) {
          expect(deviceCredentialStore.lastRestoredToken, recoveryToken);
          expect(activeHouseholdStore.cleared, isTrue);
          expect(tokenStorage.storedTokens!.accessToken, 'access');
        },
      );

      test('recoverFromToken_withInvalidToken_throwsAndLeavesTheGlobalAuthStateUntouched', () async {
        deviceCredentialStore.restoreErrorToThrow = const InvalidRecoveryToken();
        final cubit = buildCubit();
        addTearDown(cubit.close);
        final emittedStates = <AuthState>[];
        final subscription = cubit.stream.listen(emittedStates.add);

        await expectLater(
          cubit.recoverFromToken('not-a-valid-token'),
          throwsA(isA<InvalidRecoveryToken>()),
        );

        // AC3: an invalid token changes nothing — no inProgress/failure emit (which would tear
        // down the current session), no active-household clear, and no sign-in attempt. The caller
        // (RecoverAccountPage) shows the error inline instead.
        expect(emittedStates, isEmpty);
        expect(activeHouseholdStore.cleared, isFalse);
        expect(tokenStorage.storedTokens, isNull);
        await subscription.cancel();
      });
    });
  });
}
