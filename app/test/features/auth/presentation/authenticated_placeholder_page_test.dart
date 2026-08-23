import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/auth/data/oidc_tokens.dart';
import 'package:sgart/features/auth/presentation/auth_cubit.dart';
import 'package:sgart/features/auth/presentation/auth_state.dart';
import 'package:sgart/features/auth/presentation/authenticated_placeholder_page.dart';

import '../../../support/fake_auth_dependencies.dart';
import '../../../support/widget_test_harness.dart';

void main() {
  group('AuthenticatedPlaceholderPage', () {
    late FakeOidcClient oidcClient;
    late FakeSecureTokenStorage tokenStorage;
    late FakeIdentityApi identityApi;
    late AuthCubit cubit;

    setUp(() {
      oidcClient = FakeOidcClient();
      tokenStorage = FakeSecureTokenStorage();
      identityApi = FakeIdentityApi();
      tokenStorage.storedTokens = null;
      cubit = AuthCubit(oidcClient: oidcClient, tokenStorage: tokenStorage, identityApi: identityApi);
    });

    tearDown(() => cubit.close());

    Widget buildSubject() => wrapForTesting(
          BlocProvider<AuthCubit>.value(
            value: cubit,
            child: const AuthenticatedPlaceholderPage(displayName: 'Anna Testperson'),
          ),
        );

    testWidgets('rendersTheLiveDisplayNameAndSignOutButton', (tester) async {
      await tester.pumpWidget(buildSubject());

      expect(find.text('Angemeldet als Anna Testperson'), findsOneWidget);
      expect(find.text('Abmelden'), findsOneWidget);
    });

    testWidgets('fallsBackToAGenericLabelWhenTheDisplayNameIsBlank', (tester) async {
      await tester.pumpWidget(wrapForTesting(
        BlocProvider<AuthCubit>.value(
          value: cubit,
          child: const AuthenticatedPlaceholderPage(displayName: ''),
        ),
      ));

      expect(find.text('Angemeldet'), findsOneWidget);
      expect(find.textContaining('Angemeldet als'), findsNothing);
    });

    testWidgets('tappingSignOutClearsTheFakeSecureStoreAndReturnsToUnauthenticated', (tester) async {
      oidcClient.endSessionCalled = false;
      tokenStorage.storedTokens = const OidcTokens(accessToken: 'access', idToken: 'id-token');
      await tester.pumpWidget(buildSubject());

      await tester.tap(find.byKey(const Key('sign-out-button')));
      await tester.pump();
      await tester.pump();

      expect(tokenStorage.cleared, isTrue);
      expect(oidcClient.endSessionCalled, isTrue);
      expect(cubit.state.status, AuthStatus.unauthenticated);
    });
  });
}
