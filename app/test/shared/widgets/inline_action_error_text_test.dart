import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/shared/errors/app_error.dart';
import 'package:sgart/shared/widgets/inline_action_error_text.dart';
import 'package:sgart/theme/tokens/sgart_colors.dart';

import '../../support/widget_test_harness.dart';

const _duplicateError = AppError(code: 'item.duplicate', message: 'debug');
const _duplicateMessage = 'Diesen Artikel mit derselben Notiz gibt es bereits auf der Liste.';

void main() {
  group('InlineActionErrorText', () {
    Widget buildSubject(AppError? error) => wrapForTesting(Scaffold(body: InlineActionErrorText(error: error)));

    testWidgets('showsTheLocalizedMessageInTheErrorTextColourSmallerThanBodyText', (tester) async {
      await tester.pumpWidget(buildSubject(_duplicateError));

      final text = tester.widget<Text>(find.text(_duplicateMessage));
      expect(text.style?.color, SgartColors.light().onErrorTint);
      final bodyFontSize = Theme.of(tester.element(find.text(_duplicateMessage))).textTheme.bodyMedium!.fontSize!;
      expect(text.style?.fontSize, lessThan(bodyFontSize));
    });

    testWidgets('carriesTheKeyTheOwningScreenGivesTheMessage', (tester) async {
      await tester.pumpWidget(
        wrapForTesting(const Scaffold(body: InlineActionErrorText(error: _duplicateError, textKey: Key('members-action-error')))),
      );

      expect(find.byKey(const Key('members-action-error')), findsOneWidget);
      expect(find.byKey(const Key('item-list-action-error')), findsNothing);
    });

    testWidgets('rendersNothingWhenThereIsNoError', (tester) async {
      await tester.pumpWidget(buildSubject(null));

      expect(find.byKey(const Key('item-list-action-error')), findsNothing);
    });

    testWidgets('isMarkedAsALiveRegionSoScreenReadersAnnounceIt', (tester) async {
      final semantics = tester.ensureSemantics();
      await tester.pumpWidget(buildSubject(_duplicateError));

      expect(
        tester.getSemantics(find.byKey(const Key('item-list-action-error'))),
        matchesSemantics(isLiveRegion: true, label: _duplicateMessage),
      );
      semantics.dispose();
    });
  });
}
