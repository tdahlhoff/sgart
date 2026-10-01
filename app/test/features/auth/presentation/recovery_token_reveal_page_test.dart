import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/auth/data/device_credential_store.dart';
import 'package:sgart/features/auth/data/recovery_token.dart';
import 'package:sgart/features/auth/presentation/recovery_token_reveal_page.dart';
import 'package:sgart/l10n/gen/app_localizations.dart';
import 'package:sgart/shared/platform/screen_capture_guard.dart';

import '../../../support/fake_auth_dependencies.dart';
import '../../../support/widget_test_harness.dart';

void main() {
  group('RecoveryTokenRevealPage', () {
    late FakeDeviceCredentialStore deviceCredentialStore;
    late String token;

    setUp(() async {
      // A fixed, synthetic test-vector token (zero entropy) — never a real recovery token
      // (CLAUDE.md §6, DSGVO in tests).
      token = await RecoveryToken.format(Uint8List(16));
      deviceCredentialStore = FakeDeviceCredentialStore()..tokenToReturn = token;
    });

    Widget buildSubject() => wrapForTesting(
          RepositoryProvider<DeviceCredentialStore>.value(
            value: deviceCredentialStore,
            child: const RecoveryTokenRevealPage(),
          ),
        );

    testWidgets('revealPage_showsTheSingleTokenAndUnrecoverableWarning', (tester) async {
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('recovery-token-value')), findsOneWidget);
      // The token renders as one SelectableText per hyphen-group (so a narrow screen only wraps
      // at a hyphen, never mid-group) rather than as a single Text with the full token string —
      // reassemble the groups, in display order, to confirm the full token is shown intact.
      final groupFinder = find.descendant(
        of: find.byKey(const Key('recovery-token-value')),
        matching: find.byType(SelectableText),
      );
      final displayedGroups =
          tester.widgetList<SelectableText>(groupFinder).map((widget) => widget.data).toList();
      expect(displayedGroups.join('-'), token);
      expect(find.byKey(const Key('recovery-token-warning')), findsOneWidget);
    });

    testWidgets('revealPage_blocksScreenCaptureWhileOpenAndReleasesItWhenClosed', (tester) async {
      final nativeCalls = <String>[];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        ScreenCaptureGuard.channel,
        (call) async {
          nativeCalls.add(call.method);
          return null;
        },
      );
      addTearDown(
        () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
            .setMockMethodCallHandler(ScreenCaptureGuard.channel, null),
      );

      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();
      expect(nativeCalls, ['protect']);

      await tester.pumpWidget(const SizedBox());
      expect(nativeCalls, ['protect', 'release']);
    });

    testWidgets('revealPage_exposesTheTokenAsASingleAccessibilitySemanticsLabel', (tester) async {
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      final localizations = AppLocalizations.of(tester.element(find.byType(RecoveryTokenRevealPage)));
      expect(find.bySemanticsLabel(localizations.recoveryTokenValueSemanticLabel(token)), findsOneWidget);
    });

    testWidgets('revealPage_copyButtonCopiesTheTokenToTheClipboard', (tester) async {
      final copiedTexts = <String>[];
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
        if (call.method == 'Clipboard.setData') {
          copiedTexts.add((call.arguments as Map)['text'] as String);
        }
        return null;
      });
      addTearDown(() => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, null));

      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('recovery-token-copy-button')));
      await tester.pumpAndSettle();

      expect(copiedTexts, [token]);
      final localizations = AppLocalizations.of(tester.element(find.byType(RecoveryTokenRevealPage)));
      expect(find.text(localizations.recoveryTokenCopiedSnackBar), findsOneWidget);
    });

    testWidgets('rendersFromTheLocalStoreAloneWithNoUnhandledException', (tester) async {
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull);
    });

    testWidgets('showsAnErrorInsteadOfSpinningForeverWhenTheStoreReadFails', (tester) async {
      deviceCredentialStore.recoveryTokenErrorToThrow = Exception('secure storage unavailable');

      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('recovery-token-load-error')), findsOneWidget);
      expect(find.byKey(const Key('recovery-token-loading-indicator')), findsNothing);
    });
  });
}
