import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/auth/data/caller_identity.dart';
import 'package:sgart/features/auth/data/oidc_tokens.dart';
import 'package:sgart/features/auth/presentation/auth_cubit.dart';
import 'package:sgart/features/auth/presentation/auth_gate.dart';

import '../../../support/fake_auth_dependencies.dart';
import '../../../support/widget_test_harness.dart';

void main() {
  group('AuthGateBody', () {
    late FakeOidcClient oidcClient;
    late FakeSecureTokenStorage tokenStorage;
    late FakeIdentityApi identityApi;
    late AuthCubit cubit;

    setUp(() {
      oidcClient = FakeOidcClient();
      tokenStorage = FakeSecureTokenStorage();
      identityApi = FakeIdentityApi();
      cubit = AuthCubit(oidcClient: oidcClient, tokenStorage: tokenStorage, identityApi: identityApi);
    });

    tearDown(() => cubit.close());

    Widget buildSubject() => wrapForTesting(
          BlocProvider<AuthCubit>.value(value: cubit, child: const AuthGateBody()),
        );

    testWidgets('showsTheSignInGateWhenUnauthenticated', (tester) async {
      await tester.pumpWidget(buildSubject());

      expect(find.text('Anmelden'), findsOneWidget);
      expect(find.text('Abmelden'), findsNothing);
    });

    testWidgets('switchesToTheAuthenticatedPlaceholderOnceSignedIn', (tester) async {
      oidcClient.tokensToReturn = const OidcTokens(accessToken: 'access');
      identityApi.identityToReturn =
          const CallerIdentity(keycloakUserId: 'sub-1', displayName: 'Anna Testperson', email: 'anna@example.test');
      await tester.pumpWidget(buildSubject());

      await tester.tap(find.byKey(const Key('sign-in-button')));
      await tester.pump();
      await tester.pump();

      expect(find.text('Angemeldet als Anna Testperson'), findsOneWidget);
      expect(find.text('Anmelden'), findsNothing);
    });
  });
}
