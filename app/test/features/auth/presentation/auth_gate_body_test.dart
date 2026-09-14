import 'dart:async';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/auth/data/caller_identity.dart';
import 'package:sgart/features/auth/data/oidc_tokens.dart';
import 'package:sgart/features/auth/data/recovery_phrase.dart';
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
    late FakeDeviceCredentialStore deviceCredentialStore;
    late FakeActiveHouseholdStore activeHouseholdStore;
    late AuthCubit cubit;

    setUp(() {
      oidcClient = FakeOidcClient();
      tokenStorage = FakeSecureTokenStorage();
      identityApi = FakeIdentityApi();
      deviceCredentialStore = FakeDeviceCredentialStore();
      activeHouseholdStore = FakeActiveHouseholdStore();
      cubit = AuthCubit(
        oidcClient: oidcClient,
        tokenStorage: tokenStorage,
        identityApi: identityApi,
        deviceCredentialStore: deviceCredentialStore,
        activeHouseholdStore: activeHouseholdStore,
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

      expect(find.byKey(const Key('sign-in-progress-indicator')), findsOneWidget);
    });

    testWidgets('switchesAwayFromTheSignInGateOnceSignedIn', (tester) async {
      oidcClient.tokensToReturn = const OidcTokens(accessToken: 'access');
      identityApi.identityToReturn =
          const CallerIdentity(keycloakUserId: 'sub-1', displayName: 'Anna Testperson', email: 'anna@example.test');
      await tester.pumpWidget(buildSubject());

      // No button to tap (Story 7.1, AC1: zero input) — sign-in is driven by AuthCubit.bootstrap()
      // in the real app; this test drives it directly to prove AuthGateBody's own state-switching.
      await cubit.signIn();
      await tester.pump();
      await tester.pump();

      expect(find.byKey(const Key('sign-in-progress-indicator')), findsNothing);
      expect(find.text('authenticated-placeholder'), findsOneWidget);
    });

    // Task Manifest: "Confirm the swap routes correctly" (Story 7.2, AC3, D-E) — a successful
    // recoverFromPhrase must fully unmount and remount the authenticated subtree (the real
    // FirstRunRouter in production), not just rebuild it in place, so it re-bootstraps against the
    // recovered identity's access token rather than reusing stale state. [_MountCounter] stands in
    // for FirstRunRouter here (which itself builds a real HTTP client, CLAUDE.md §6) — its
    // initState only re-runs on a genuine remount.
    testWidgets('recoverFromPhraseRemountsTheAuthenticatedSubtreeForTheRecoveredIdentity', (tester) async {
      final recoveryWords = RecoveryPhrase.wordsFromEntropy(Uint8List(32));
      final delayedOidcClient = _CompleterControlledFakeOidcClient()
        ..tokensToReturn = const OidcTokens(accessToken: 'access-throwaway');
      identityApi.identityToReturn = const CallerIdentity(
          keycloakUserId: 'sub-throwaway', displayName: 'Throwaway', email: 'throwaway@example.test');
      activeHouseholdStore.activeId = 'throwaway-household';
      final recoveryCubit = AuthCubit(
        oidcClient: delayedOidcClient,
        tokenStorage: tokenStorage,
        identityApi: identityApi,
        deviceCredentialStore: deviceCredentialStore,
        activeHouseholdStore: activeHouseholdStore,
      );
      addTearDown(recoveryCubit.close);
      var mountCount = 0;

      await tester.pumpWidget(wrapForTesting(
        BlocProvider<AuthCubit>.value(
          value: recoveryCubit,
          child: AuthGateBody(
            authenticatedBuilder: (context) => _MountCounter(
              onMount: () => mountCount++,
              label: context.read<AuthCubit>().state.keycloakUserId!,
            ),
          ),
        ),
      ));

      await recoveryCubit.signIn();
      await tester.pump();
      await tester.pump();

      expect(find.text('sub-throwaway'), findsOneWidget);
      expect(mountCount, 1);

      // The next signIn() (driven by recoverFromPhrase) resolves to a different, existing account —
      // but blocks on a Completer we control, so the test can pump exactly on the transient
      // `inProgress` frame instead of the fakes' instant microtask resolution collapsing straight
      // from authenticated-throwaway to authenticated-recovered (which a single pump() would never
      // observe as a distinct frame, since a real unmount/remount can only be proven by the
      // element tree actually diffing against a structurally different widget — SignInPage — at
      // some point, not by comparing the start and end states after the fact).
      identityApi.identityToReturn = const CallerIdentity(
          keycloakUserId: 'sub-recovered', displayName: 'Recovered Person', email: 'recovered@example.test');
      final pendingSignIn = Completer<OidcTokens>();
      delayedOidcClient.pendingSignIn = pendingSignIn;

      final recoverFuture = recoveryCubit.recoverFromPhrase(recoveryWords);
      await tester.pump();
      await tester.pump();
      // Briefly back at the sign-in gate — the previous authenticated subtree is disposed here.
      expect(find.byKey(const Key('sign-in-progress-indicator')), findsOneWidget);
      expect(mountCount, 1);

      pendingSignIn.complete(const OidcTokens(accessToken: 'access-recovered'));
      await recoverFuture;
      await tester.pump();
      await tester.pump();

      expect(find.text('sub-recovered'), findsOneWidget);
      expect(mountCount, 2);
      expect(activeHouseholdStore.cleared, isTrue);
    });
  });
}

/// [FakeOidcClient.signIn] blocks on [pendingSignIn] when set, instead of resolving instantly —
/// gives a test a real point to pump on mid-flight, distinct from the fakes' default synchronous
/// (microtask-only) resolution that a single `pump()` would otherwise collapse straight through.
class _CompleterControlledFakeOidcClient extends FakeOidcClient {
  Completer<OidcTokens>? pendingSignIn;

  @override
  Future<OidcTokens> signIn() {
    final pending = pendingSignIn;
    return pending != null ? pending.future : super.signIn();
  }
}

/// Calls [onMount] exactly once per mount (in `initState`) and renders [label] — a network-free
/// stand-in for [FirstRunRouter]'s own per-identity bootstrap, used only to prove the remount
/// mechanics of [AuthGateBody]'s authenticated/sign-in switch.
class _MountCounter extends StatefulWidget {
  const _MountCounter({required this.onMount, required this.label});

  final VoidCallback onMount;
  final String label;

  @override
  State<_MountCounter> createState() => _MountCounterState();
}

class _MountCounterState extends State<_MountCounter> {
  @override
  void initState() {
    super.initState();
    widget.onMount();
  }

  @override
  Widget build(BuildContext context) => Text(widget.label);
}
