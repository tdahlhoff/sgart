import 'package:bloc_test/bloc_test.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/auth/data/caller_identity.dart';
import 'package:sgart/features/auth/data/oidc_tokens.dart';
import 'package:sgart/features/auth/presentation/auth_cubit.dart';
import 'package:sgart/features/auth/presentation/auth_state.dart';
import 'package:sgart/shared/errors/app_error.dart';
import 'package:sgart/shared/http/app_exception.dart';

import '../../../support/fake_auth_dependencies.dart';
import '../../../support/fake_push_notifications.dart';

void main() {
  group('AuthState', () {
    test('authenticatedCarriesTheEmailAlongsideDisplayNameAndKeycloakUserId', () {
      const state = AuthState.authenticated('Anna Testperson', 'sub-1', 'anna@example.test');

      expect(state.displayName, 'Anna Testperson');
      expect(state.keycloakUserId, 'sub-1');
      expect(state.email, 'anna@example.test');
    });

    test('equalityAndHashCodeIncludeTheEmail', () {
      const first = AuthState.authenticated('Anna', 'sub-1', 'anna@example.test');
      const sameEmail = AuthState.authenticated('Anna', 'sub-1', 'anna@example.test');
      const differentEmail = AuthState.authenticated('Anna', 'sub-1', 'other@example.test');

      expect(first, sameEmail);
      expect(first.hashCode, sameEmail.hashCode);
      expect(first, isNot(differentEmail));
    });
  });

  group('AuthCubit', () {
    late FakeOidcClient oidcClient;
    late FakeSecureTokenStorage tokenStorage;
    late FakeIdentityApi identityApi;

    setUp(() {
      oidcClient = FakeOidcClient();
      tokenStorage = FakeSecureTokenStorage();
      identityApi = FakeIdentityApi();
    });

    AuthCubit buildCubit() => AuthCubit(
          oidcClient: oidcClient,
          tokenStorage: tokenStorage,
          identityApi: identityApi,
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
            keycloakUserId: 'sub-1', displayName: 'Anna Testperson', email: 'anna@example.test');
        return buildCubit();
      },
      act: (cubit) => cubit.signIn(),
      expect: () => [
        const AuthState.inProgress(),
        const AuthState.authenticated('Anna Testperson', 'sub-1', 'anna@example.test'),
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
            const CallerIdentity(keycloakUserId: 'sub-1', displayName: 'Anna', email: 'anna@example.test');
        return buildCubit();
      },
      act: (cubit) => cubit.bootstrap(),
      expect: () => [const AuthState.authenticated('Anna', 'sub-1', 'anna@example.test')],
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
            keycloakUserId: 'sub-1', displayName: 'Anna Testperson', email: 'anna@example.test');
        return buildCubit();
      },
      act: (cubit) => cubit.bootstrap(),
      expect: () => [
        const AuthState.inProgress(),
        const AuthState.authenticated('Anna Testperson', 'sub-1', 'anna@example.test'),
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
            const CallerIdentity(keycloakUserId: 'sub-1', displayName: 'Anna', email: 'anna@example.test');
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
            const CallerIdentity(keycloakUserId: 'sub-1', displayName: 'Anna', email: 'anna@example.test');
        final cubit = buildCubit();
        await cubit.signIn();

        final refreshed = await cubit.tryRefreshTokens();

        expect(refreshed, isFalse);
        await cubit.close();
      });

      test('returnsFalseWhenTheRefreshCallFails', () async {
        oidcClient.tokensToReturn = const OidcTokens(accessToken: 'access', refreshToken: 'refresh');
        identityApi.identityToReturn =
            const CallerIdentity(keycloakUserId: 'sub-1', displayName: 'Anna', email: 'anna@example.test');
        oidcClient.refreshErrorToThrow = StateError('refresh token revoked');
        final cubit = buildCubit();
        await cubit.signIn();

        final refreshed = await cubit.tryRefreshTokens();

        expect(refreshed, isFalse);
        await cubit.close();
      });
    });

    test('doesNotEmitAfterTheCubitIsClosedMidSignIn', () async {
      oidcClient.tokensToReturn = const OidcTokens(accessToken: 'access');
      identityApi.identityToReturn =
          const CallerIdentity(keycloakUserId: 'sub-1', displayName: 'Anna', email: 'anna@example.test');
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
            pushNotifications: pushNotifications,
          );

      test('signIn_registersTheDeviceTokenOnceAuthenticated', () async {
        oidcClient.tokensToReturn = const OidcTokens(accessToken: 'access');
        identityApi.identityToReturn =
            const CallerIdentity(keycloakUserId: 'sub-1', displayName: 'Anna', email: 'anna@example.test');
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
            const CallerIdentity(keycloakUserId: 'sub-1', displayName: 'Anna', email: 'anna@example.test');
        final cubit = buildCubit(); // no pushNotifications injected

        await cubit.signIn();

        await cubit.close();
      });
    });
  });
}
