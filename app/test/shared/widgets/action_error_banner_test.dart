import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/shared/errors/app_error.dart';
import 'package:sgart/shared/widgets/action_error_banner.dart';
import 'package:sgart/theme/tokens/sgart_colors.dart';

import '../../support/widget_test_harness.dart';

const _duplicateError = AppError(code: 'item.duplicate', message: 'debug');
const _duplicateMessage = 'Diesen Artikel mit derselben Notiz gibt es bereits auf der Liste.';

void main() {
  group('ActionErrorBanner', () {
    Widget buildSubject({AppError? error, VoidCallback? onDismiss, bool reduceMotion = false}) {
      return wrapForTesting(
        Builder(
          builder: (context) => MediaQuery(
            data: MediaQuery.of(context).copyWith(disableAnimations: reduceMotion),
            child: Scaffold(body: ActionErrorBanner(error: error, onDismiss: onDismiss)),
          ),
        ),
      );
    }

    testWidgets('showsTheLocalizedMessageWithAnErrorIconOnATintedContainer', (tester) async {
      await tester.pumpWidget(buildSubject(error: _duplicateError));
      await tester.pumpAndSettle();

      expect(find.text(_duplicateMessage), findsOneWidget);
      expect(find.byIcon(Icons.error_outline), findsOneWidget);
      final container = tester.widget<DecoratedBox>(find.byKey(const Key('action-error-banner-surface')));
      final decoration = container.decoration as BoxDecoration;
      expect(decoration.color, SgartColors.light().error.withValues(alpha: SgartColors.tintAlpha));
    });

    testWidgets('rendersNothingWhenThereIsNoError', (tester) async {
      await tester.pumpWidget(buildSubject());
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('action-error-banner-surface')), findsNothing);
    });

    testWidgets('shakesSideToSideWhenANewErrorArrivesAndThenComesToRest', (tester) async {
      await tester.pumpWidget(buildSubject());
      await tester.pumpWidget(buildSubject(error: _duplicateError));
      await tester.pump(const Duration(milliseconds: 250));
      final restingLeft = tester.getTopLeft(find.byKey(const Key('action-error-banner-surface'))).dx;
      final shakenOffsets = <double>{};
      for (var elapsed = 0; elapsed < 300; elapsed += 20) {
        await tester.pump(const Duration(milliseconds: 20));
        shakenOffsets.add(tester.getTopLeft(find.byKey(const Key('action-error-banner-surface'))).dx);
      }
      await tester.pumpAndSettle();
      final settledLeft = tester.getTopLeft(find.byKey(const Key('action-error-banner-surface'))).dx;

      expect(shakenOffsets.length, greaterThan(2), reason: 'the banner moves while shaking');
      expect(shakenOffsets.any((offset) => offset != settledLeft), isTrue);
      expect(settledLeft, 16, reason: 'the banner ends exactly where it sits at rest');
      expect(restingLeft, isNotNull);
    });

    testWidgets('appearsAtRestWithoutAnimationWhenTheDeviceRequestsReducedMotion', (tester) async {
      await tester.pumpWidget(buildSubject(reduceMotion: true));
      await tester.pumpWidget(buildSubject(error: _duplicateError, reduceMotion: true));
      await tester.pump();

      expect(tester.getTopLeft(find.byKey(const Key('action-error-banner-surface'))).dx, 16);
      expect(tester.hasRunningAnimations, isFalse);
    });

    testWidgets('announcesTheMessageToScreenReadersOnceWhenItAppears', (tester) async {
      await tester.pumpWidget(buildSubject());
      await tester.pumpWidget(buildSubject(error: _duplicateError));
      await tester.pumpAndSettle();
      await tester.pumpWidget(buildSubject(error: _duplicateError));
      await tester.pumpAndSettle();

      final announcements = tester.takeAnnouncements();

      expect(announcements.map((announcement) => announcement.message), [_duplicateMessage]);
    });

    testWidgets('isMarkedAsALiveRegion', (tester) async {
      final semantics = tester.ensureSemantics();
      await tester.pumpWidget(buildSubject(error: _duplicateError));
      await tester.pumpAndSettle();

      expect(
        tester.getSemantics(find.byKey(const Key('action-error-banner-semantics'))),
        matchesSemantics(isLiveRegion: true, label: _duplicateMessage),
      );
      semantics.dispose();
    });

    testWidgets('theCloseButtonCallsOnDismiss', (tester) async {
      var dismissals = 0;
      await tester.pumpWidget(buildSubject(error: _duplicateError, onDismiss: () => dismissals++));
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('action-error-banner-dismiss')));

      expect(dismissals, 1);
    });

    testWidgets('showsNoCloseButtonWhenNothingCanDismissIt', (tester) async {
      await tester.pumpWidget(buildSubject(error: _duplicateError));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('action-error-banner-dismiss')), findsNothing);
    });
  });
}
