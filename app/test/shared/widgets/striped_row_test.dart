import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/shared/widgets/striped_row.dart';
import 'package:sgart/theme/tokens/sgart_colors.dart';

import '../../support/widget_test_harness.dart';

void main() {
  group('StripedRow', () {
    Widget buildSubject(int index) =>
        wrapForTesting(Scaffold(body: StripedRow(index: index, child: const Text('Zeile'))));

    testWidgets('anEvenRowKeepsTheBareBackground', (tester) async {
      await tester.pumpWidget(buildSubject(0));

      expect(find.byKey(const Key('striped-row-tint')), findsNothing);
      expect(find.text('Zeile'), findsOneWidget);
    });

    testWidgets('anOddRowGetsAFaintNeutralTint', (tester) async {
      await tester.pumpWidget(buildSubject(1));

      final tint = tester.widget<Material>(find.byKey(const Key('striped-row-tint')));
      expect(tint.color, SgartColors.light().textSecondary.withValues(alpha: StripedRow.tintAlpha));
      expect(find.text('Zeile'), findsOneWidget);
    });
  });
}
