import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/auth/data/caller_identity.dart';
import 'package:sgart/features/auth/data/oidc_tokens.dart';
import 'package:sgart/features/auth/presentation/auth_cubit.dart';
import 'package:sgart/features/auth/presentation/auth_gate.dart';

import '../../../support/fake_auth_dependencies.dart';
import '../../../support/fake_households_dependencies.dart';
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
      cubit = AuthCubit(
        oidcClient: oidcClient,
        tokenStorage: tokenStorage,
        identityApi: identityApi,
        activeHouseholdStore: FakeActiveHouseholdStore(),
      );
    });

    tearDown(() => cubit.close());

    // The real authenticated destination (FirstRunRouter) builds a real HTTP client — swapped
    // for a network-free placeholder here so this test proves only AuthGateBody's own switching
    // logic, never touching the network (CLAUDE.md §6). FirstRunRouter's own behavior is covered
    // separately (no real dependencies) in first_run_router_test.dart.
    Widget buildSubject() => wrapForTesting(
          BlocProvider<AuthCubit>.value(
            value: cubit,
            child: AuthGateBody(authenticatedBuilder: (_) => const Text('authenticated-placeholder')),
          ),
        );

    testWidgets('showsTheSignInGateWhenUnauthenticated', (tester) async {
      await tester.pumpWidget(buildSubject());

      expect(find.text('Anmelden'), findsOneWidget);
      expect(find.text('Abmelden'), findsNothing);
    });

    testWidgets('switchesAwayFromTheSignInGateOnceSignedIn', (tester) async {
      oidcClient.tokensToReturn = const OidcTokens(accessToken: 'access');
      identityApi.identityToReturn =
          const CallerIdentity(keycloakUserId: 'sub-1', displayName: 'Anna Testperson', email: 'anna@example.test');
      await tester.pumpWidget(buildSubject());

      await tester.tap(find.byKey(const Key('sign-in-button')));
      await tester.pump();
      await tester.pump();

      expect(find.text('Anmelden'), findsNothing);
      expect(find.text('authenticated-placeholder'), findsOneWidget);
    });
  });
}
