import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/auth/data/caller_identity.dart';
import 'package:sgart/features/auth/data/oidc_tokens.dart';
import 'package:sgart/features/auth/presentation/auth_cubit.dart';
import 'package:sgart/features/auth/presentation/auth_state.dart';
import 'package:sgart/features/auth/presentation/sign_in_page.dart';
import 'package:sgart/shared/errors/app_error.dart';
import 'package:sgart/shared/http/app_exception.dart';

import '../../../support/fake_auth_dependencies.dart';
import '../../../support/fake_households_dependencies.dart';
import '../../../support/widget_test_harness.dart';

void main() {
  group('SignInPage', () {
    late FakeOidcClient oidcClient;
    late FakeSecureTokenStorage tokenStorage;
    late FakeIdentityApi identityApi;
    late AuthCubit cubit;

    setUp(() {
      oidcClient = FakeOidcClient();
      tokenStorage = FakeSecureTokenStorage();
      identityApi = FakeIdentityApi();
      cubit = AuthCubit(
        oidcClient: oidcClient,
        tokenStorage: tokenStorage,
        identityApi: identityApi,
        activeHouseholdStore: FakeActiveHouseholdStore(),
      );
    });

    tearDown(() => cubit.close());

    Widget buildSubject() => wrapForTesting(
          BlocProvider<AuthCubit>.value(value: cubit, child: const SignInPage()),
        );

    testWidgets('rendersTheSignInHeadingAndButton', (tester) async {
      await tester.pumpWidget(buildSubject());

      expect(find.text('Willkommen bei SGART'), findsOneWidget);
      expect(find.text('Anmelden'), findsOneWidget);
    });

    testWidgets('tappingTheButtonStartsSignInAndReachesTheAuthenticatedState', (tester) async {
      oidcClient.tokensToReturn = const OidcTokens(accessToken: 'access');
      identityApi.identityToReturn =
          const CallerIdentity(keycloakUserId: 'sub-1', displayName: 'Anna Testperson', email: 'anna@example.test');
      await tester.pumpWidget(buildSubject());

      await tester.tap(find.byKey(const Key('sign-in-button')));
      await tester.pump();
      await tester.pump();

      expect(cubit.state.status, AuthStatus.authenticated);
      expect(cubit.state.displayName, 'Anna Testperson');
    });

    testWidgets('showsALocalizedErrorMessageWhenSignInFails', (tester) async {
      oidcClient.signInErrorToThrow = const AppException(AppError(code: 'identity.notAMember', message: 'debug'));
      await tester.pumpWidget(buildSubject());

      await tester.tap(find.byKey(const Key('sign-in-button')));
      await tester.pump();
      await tester.pump();

      expect(find.byKey(const Key('sign-in-error')), findsOneWidget);
      expect(find.text('Es ist ein Fehler aufgetreten. Bitte versuche es erneut.'), findsOneWidget);
    });
  });
}
