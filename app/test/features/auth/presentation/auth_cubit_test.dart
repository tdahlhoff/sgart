import 'package:bloc_test/bloc_test.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/auth/data/caller_identity.dart';
import 'package:sgart/features/auth/data/oidc_tokens.dart';
import 'package:sgart/features/auth/presentation/auth_cubit.dart';
import 'package:sgart/features/auth/presentation/auth_state.dart';
import 'package:sgart/shared/errors/app_error.dart';
import 'package:sgart/shared/http/app_exception.dart';

import '../../../support/fake_auth_dependencies.dart';
import '../../../support/fake_households_dependencies.dart';

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
    late FakeActiveHouseholdStore activeHouseholdStore;

    setUp(() {
      oidcClient = FakeOidcClient();
      tokenStorage = FakeSecureTokenStorage();
      identityApi = FakeIdentityApi();
      activeHouseholdStore = FakeActiveHouseholdStore();
    });

    AuthCubit buildCubit() => AuthCubit(
          oidcClient: oidcClient,
          tokenStorage: tokenStorage,
          identityApi: identityApi,
          activeHouseholdStore: activeHouseholdStore,
        );

    test('startsUnauthenticated', () {
      expect(buildCubit().state, const AuthState.unauthenticated());
      buildCubit().close();
    });

    blocTest<AuthCubit, AuthState>(
      'signIn_authenticatesAndStoresTheTokensOnSuccess',
      build: () {
        oidcClient.tokensToReturn =
            const OidcTokens(accessToken: 'access', refreshToken: 'refresh', idToken: 'id');
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
      'signOut_clearsStorageEndsTheSsoSessionAndReturnsToUnauthenticated',
      build: () {
        tokenStorage.storedTokens = const OidcTokens(accessToken: 'access', idToken: 'id-token');
        identityApi.identityToReturn =
            const CallerIdentity(keycloakUserId: 'sub-1', displayName: 'Anna', email: 'anna@example.test');
        return buildCubit();
      },
      act: (cubit) async {
        await cubit.bootstrap();
        await cubit.signOut();
      },
      expect: () => [const AuthState.authenticated('Anna', 'sub-1', 'anna@example.test'), const AuthState.unauthenticated()],
      verify: (_) {
        expect(tokenStorage.cleared, isTrue);
        expect(oidcClient.endSessionCalled, isTrue);
        expect(oidcClient.lastEndSessionIdToken, 'id-token');
      },
    );

    blocTest<AuthCubit, AuthState>(
      'signOutClearsTheStoredActiveHousehold',
      build: () {
        activeHouseholdStore.activeId = 'household-1';
        tokenStorage.storedTokens = const OidcTokens(accessToken: 'access', idToken: 'id-token');
        identityApi.identityToReturn =
            const CallerIdentity(keycloakUserId: 'sub-1', displayName: 'Anna', email: 'anna@example.test');
        return buildCubit();
      },
      act: (cubit) async {
        await cubit.bootstrap();
        await cubit.signOut();
      },
      expect: () => [const AuthState.authenticated('Anna', 'sub-1', 'anna@example.test'), const AuthState.unauthenticated()],
      verify: (_) {
        expect(activeHouseholdStore.cleared, isTrue);
        expect(activeHouseholdStore.activeId, isNull);
      },
    );

    blocTest<AuthCubit, AuthState>(
      'signOut_stillClearsLocalTokensWhenEndingTheSsoSessionFails',
      build: () {
        oidcClient.endSessionErrorToThrow = StateError('network down');
        tokenStorage.storedTokens = const OidcTokens(accessToken: 'access', idToken: 'id-token');
        identityApi.identityToReturn =
            const CallerIdentity(keycloakUserId: 'sub-1', displayName: 'Anna', email: 'anna@example.test');
        return buildCubit();
      },
      act: (cubit) async {
        await cubit.bootstrap();
        await cubit.signOut();
      },
      expect: () => [const AuthState.authenticated('Anna', 'sub-1', 'anna@example.test'), const AuthState.unauthenticated()],
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

    blocTest<AuthCubit, AuthState>(
      'bootstrap_staysUnauthenticatedWhenNoTokensAreStored',
      build: buildCubit,
      act: (cubit) => cubit.bootstrap(),
      expect: () => [],
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

    blocTest<AuthCubit, AuthState>(
      'bootstrap_refreshesTheAccessTokenAndResumesWhenTheStoredAccessTokenIsExpired',
      build: () {
        tokenStorage.storedTokens = const OidcTokens(accessToken: 'expired', refreshToken: 'refresh');
        identityApi.enqueue(const AppException(AppError(code: 'auth.unauthorized', message: 'expired')));
        identityApi.enqueue(
            const CallerIdentity(keycloakUserId: 'sub-1', displayName: 'Anna', email: 'anna@example.test'));
        oidcClient.refreshedTokensToReturn =
            const OidcTokens(accessToken: 'fresh', refreshToken: 'rotated');
        return buildCubit();
      },
      act: (cubit) => cubit.bootstrap(),
      expect: () => [const AuthState.authenticated('Anna', 'sub-1', 'anna@example.test')],
      verify: (_) {
        expect(oidcClient.lastRefreshToken, 'refresh');
        expect(tokenStorage.storedTokens!.accessToken, 'fresh');
        expect(identityApi.fetchMeCallCount, 2);
      },
    );

    blocTest<AuthCubit, AuthState>(
      'bootstrap_clearsTheSessionWhenTheAccessTokenIsExpiredAndTheRefreshFails',
      build: () {
        tokenStorage.storedTokens = const OidcTokens(accessToken: 'expired', refreshToken: 'refresh');
        identityApi.errorToThrow = const AppException(AppError(code: 'auth.unauthorized', message: 'expired'));
        oidcClient.refreshErrorToThrow = StateError('refresh token revoked');
        return buildCubit();
      },
      act: (cubit) => cubit.bootstrap(),
      expect: () => [const AuthState.failure(AppError(code: 'auth.unauthorized', message: 'expired'))],
      verify: (_) => expect(tokenStorage.cleared, isTrue),
    );

    test('doesNotEmitAfterTheCubitIsClosedMidSignIn', () async {
      oidcClient.tokensToReturn = const OidcTokens(accessToken: 'access');
      identityApi.identityToReturn =
          const CallerIdentity(keycloakUserId: 'sub-1', displayName: 'Anna', email: 'anna@example.test');
      final cubit = buildCubit();

      final signInFuture = cubit.signIn();
      await cubit.close();

      await signInFuture;
    });
  });
}
