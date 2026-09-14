import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/auth/data/device_credential_store.dart';
import 'package:sgart/features/auth/data/recovery_phrase.dart';
import 'package:sgart/features/auth/presentation/recovery_phrase_reveal_page.dart';

import '../../../support/fake_auth_dependencies.dart';
import '../../../support/widget_test_harness.dart';

void main() {
  group('RecoveryPhraseRevealPage', () {
    late FakeDeviceCredentialStore deviceCredentialStore;
    late List<String> words;

    setUp(() {
      // A fixed, synthetic test-vector mnemonic (zero entropy) — never a real recovery phrase
      // (CLAUDE.md §6, DSGVO in tests).
      words = RecoveryPhrase.wordsFromEntropy(Uint8List(32));
      deviceCredentialStore = FakeDeviceCredentialStore()..wordsToReturn = words;
    });

    Widget buildSubject() => wrapForTesting(
          RepositoryProvider<DeviceCredentialStore>.value(
            value: deviceCredentialStore,
            child: const RecoveryPhraseRevealPage(),
          ),
        );

    testWidgets('revealPage_showsNumberedTwentyFourWordsAndUnrecoverableWarning', (tester) async {
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      final wrap = tester.widget<Wrap>(find.byKey(const Key('recovery-phrase-words')));
      expect(wrap.children, hasLength(24));
      expect(find.byKey(const Key('recovery-phrase-word-1')), findsOneWidget);
      expect(find.text('1. ${words[0]}'), findsOneWidget);
      expect(find.byKey(const Key('recovery-phrase-word-24')), findsOneWidget);
      expect(find.text('24. ${words[23]}'), findsOneWidget);
      expect(find.byKey(const Key('recovery-phrase-warning')), findsOneWidget);
    });

    // AC4 (reveal half): this page never constructs a Dio/HTTP client anywhere in its widget
    // tree — no such import exists in recovery_phrase_reveal_page.dart at all — so it cannot make
    // a network call by construction. The recovery half is proven at the wire boundary in
    // direct_grant_oidc_client_test.dart's `signIn_sendsOnlyThePublicKeyAndSignedChallenge…` test.
    testWidgets('rendersFromTheLocalStoreAloneWithNoUnhandledException', (tester) async {
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
    });

    testWidgets('showsAnErrorInsteadOfSpinningForeverWhenTheStoreReadFails', (tester) async {
      deviceCredentialStore.recoveryPhraseErrorToThrow = Exception('secure storage unavailable');

      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('recovery-phrase-load-error')), findsOneWidget);
      expect(find.byKey(const Key('recovery-phrase-loading-indicator')), findsNothing);
    });
  });
}
