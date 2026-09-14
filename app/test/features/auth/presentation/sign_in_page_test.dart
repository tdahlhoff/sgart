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
      );
    });

    tearDown(() => cubit.close());

    Widget buildSubject() => wrapForTesting(
          BlocProvider<AuthCubit>.value(value: cubit, child: const SignInPage()),
        );

    testWidgets('rendersTheHeadingSubtitleAndALoadingIndicatorWithNoInputAndNoButton', (tester) async {
      await tester.pumpWidget(buildSubject());

      expect(find.text('Willkommen bei SGART'), findsOneWidget);
      expect(find.byKey(const Key('sign-in-subtitle')), findsOneWidget);
      expect(find.byKey(const Key('sign-in-progress-indicator')), findsOneWidget);
      // Zero input (Story 7.1, AC1): no sign-in button, no text field, ever.
      expect(find.byType(TextField), findsNothing);
      expect(find.byKey(const Key('sign-in-button')), findsNothing);
    });

    testWidgets('automaticallyReachesTheAuthenticatedStateOnceBootstrapSignsInSilently', (tester) async {
      oidcClient.tokensToReturn = const OidcTokens(accessToken: 'access');
      identityApi.identityToReturn =
          const CallerIdentity(keycloakUserId: 'sub-1', displayName: 'Anna Testperson', email: 'anna@example.test');
      await tester.pumpWidget(buildSubject());

      await cubit.bootstrap();
      await tester.pump();
      await tester.pump();

      expect(cubit.state.status, AuthStatus.authenticated);
      expect(cubit.state.displayName, 'Anna Testperson');
    });

    testWidgets('showsALocalizedErrorMessageAndARetryButtonWhenSignInFails', (tester) async {
      oidcClient.signInErrorToThrow = const AppException(AppError(code: 'identity.notAMember', message: 'debug'));
      await tester.pumpWidget(buildSubject());

      await cubit.bootstrap();
      await tester.pump();
      await tester.pump();

      expect(find.byKey(const Key('sign-in-error')), findsOneWidget);
      expect(find.text('Es ist ein Fehler aufgetreten. Bitte versuche es erneut.'), findsOneWidget);
      expect(find.byKey(const Key('sign-in-retry-button')), findsOneWidget);
    });

    testWidgets('tappingRetryAfterAFailureRunsSignInAgain', (tester) async {
      oidcClient.signInErrorToThrow = const AppException(AppError(code: 'network.unreachable', message: 'debug'));
      await tester.pumpWidget(buildSubject());
      await cubit.bootstrap();
      await tester.pump();
      await tester.pump();
      expect(cubit.state.status, AuthStatus.failure);

      oidcClient.signInErrorToThrow = null;
      oidcClient.tokensToReturn = const OidcTokens(accessToken: 'access');
      identityApi.identityToReturn =
          const CallerIdentity(keycloakUserId: 'sub-1', displayName: 'Anna Testperson', email: 'anna@example.test');
      await tester.tap(find.byKey(const Key('sign-in-retry-button')));
      await tester.pump();
      await tester.pump();

      expect(cubit.state.status, AuthStatus.authenticated);
    });
  });
}
