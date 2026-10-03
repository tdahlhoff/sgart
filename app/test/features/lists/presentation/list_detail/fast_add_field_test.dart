import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/lists/data/item.dart';
import 'package:sgart/features/lists/data/item_suggestion.dart';
import 'package:sgart/features/lists/presentation/list_detail/fast_add_field.dart';
import 'package:sgart/features/lists/presentation/list_detail/list_detail_cubit.dart';
import 'package:sgart/features/stores/data/store_summary.dart';
import 'package:sgart/shared/errors/app_error.dart';
import 'package:sgart/shared/http/app_exception.dart';
import 'package:sgart/theme/tokens/sgart_colors.dart';
import 'package:sgart/theme/tokens/sgart_shapes.dart';

import '../../../../support/fake_item_suggestions_api.dart';
import '../../../../support/fake_items_dependencies.dart';
import '../../../../support/fake_stores_dependencies.dart';
import '../../../../support/fake_trips_dependencies.dart';
import '../../../../support/widget_test_harness.dart';

/// Widget tests for the persistent fast-add field (Story 2.5, AC2/AC3/AC4) in isolation — mirrors
/// the list_detail_page_test.dart route-level coverage but drives the widget directly.
void main() {
  group('FastAddField', () {
    late FakeItemsApi itemsApi;
    late FakeItemSuggestionsApi itemSuggestionsApi;
    late FakeStoresApi storesApi;
    late FakeTripsApi tripsApi;
    late ListDetailCubit cubit;

    setUp(() {
      itemsApi = FakeItemsApi();
      itemSuggestionsApi = FakeItemSuggestionsApi();
      storesApi = FakeStoresApi();
      tripsApi = FakeTripsApi();
    });

    tearDown(() => cubit.close());

    Widget buildSubject() {
      cubit = ListDetailCubit(
        itemsApi: itemsApi,
        itemSuggestionsApi: itemSuggestionsApi,
        storesApi: storesApi,
        tripsApi: tripsApi,
        householdId: 'household-1',
        listId: 'list-1',
        isReadOnly: false,
      );
      return wrapForTesting(
        BlocProvider<ListDetailCubit>.value(
          value: cubit,
          // Pinned at the bottom like on the real screen, with an empty "list" above it.
          child: Scaffold(
            body: Column(
              children: [
                const Expanded(child: SizedBox.expand(key: Key('list-area'))),
                FastAddField(cubit: cubit),
              ],
            ),
          ),
        ),
      );
    }

    testWidgets('typingShowsThePanelAboveWithMatchingSuggestionsAndTheAddAsNewRow', (tester) async {
      itemSuggestionsApi.suggestionsToReturn = const [
        ItemSuggestion(name: 'Milch', note: 'Bio', amount: '2', unit: 'LITRE'),
        ItemSuggestion(name: 'Milchreis', note: null, amount: '1', unit: 'PACK'),
        ItemSuggestion(name: 'Brot', note: null, amount: '1', unit: 'PACK'),
      ];
      await tester.pumpWidget(buildSubject());
      await cubit.bootstrap();
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('fast-add-field')));
      await tester.enterText(find.byKey(const Key('fast-add-field')), 'Milch');
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('fast-add-suggestion-milch')), findsOneWidget);
      expect(find.byKey(const Key('fast-add-suggestion-milchreis')), findsOneWidget);
      expect(find.byKey(const Key('fast-add-suggestion-brot')), findsNothing);
      expect(find.byKey(const Key('fast-add-new-row')), findsOneWidget);
    });

    testWidgets('tappingASuggestionCallsAddItemWithThePrefilledQuantityAndNote', (tester) async {
      itemSuggestionsApi.suggestionsToReturn = const [
        ItemSuggestion(name: 'Milch', note: 'Bio', amount: '2', unit: 'LITRE'),
      ];
      await tester.pumpWidget(buildSubject());
      await cubit.bootstrap();
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('fast-add-field')));
      await tester.enterText(find.byKey(const Key('fast-add-field')), 'Milch');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('fast-add-suggestion-milch')));
      await tester.pumpAndSettle();

      expect(itemsApi.lastAddedName, 'Milch');
      expect(itemsApi.lastAddedNote, 'Bio');
      expect(itemsApi.lastAddedAmount, '2');
      expect(itemsApi.lastAddedUnit, 'LITRE');
    });

    testWidgets('aSuggestionWithAnActiveLastUsedStoreShowsTheZuletztChipAndAddThenAssigns', (tester) async {
      storesApi.storesToReturn = const [StoreSummary(storeId: 's1', name: 'Edeka')];
      itemSuggestionsApi.suggestionsToReturn = const [
        ItemSuggestion(name: 'Milch', note: null, amount: '1', unit: 'PIECE', defaultStoreId: 's1'),
      ];
      await tester.pumpWidget(buildSubject());
      await cubit.bootstrap();
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('fast-add-field')));
      await tester.enterText(find.byKey(const Key('fast-add-field')), 'Milch');
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('fast-add-suggestion-store-milch')), findsOneWidget);
      expect(find.text('zuletzt Edeka'), findsOneWidget);

      await tester.tap(find.byKey(const Key('fast-add-suggestion-milch')));
      await tester.pumpAndSettle();

      expect(itemsApi.lastAddedName, 'Milch');
      expect(itemsApi.lastAssignedStoreId, 's1');
    });

    testWidgets('aSuggestionWithNoLastUsedStoreShowsNoZuletztChip', (tester) async {
      itemSuggestionsApi.suggestionsToReturn = const [
        ItemSuggestion(name: 'Milch', note: null, amount: '1', unit: 'PIECE'),
      ];
      await tester.pumpWidget(buildSubject());
      await cubit.bootstrap();
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('fast-add-field')));
      await tester.enterText(find.byKey(const Key('fast-add-field')), 'Milch');
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('fast-add-suggestion-store-milch')), findsNothing);
    });

    testWidgets('theAddAsNewRowAddsAnUnknownNameAsOnePiece', (tester) async {
      await tester.pumpWidget(buildSubject());
      await cubit.bootstrap();
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('fast-add-field')));
      await tester.enterText(find.byKey(const Key('fast-add-field')), 'Käse');
      await tester.pumpAndSettle();
      await tester.tap(find.byKey(const Key('fast-add-new-row')));
      await tester.pumpAndSettle();

      expect(itemsApi.lastAddedName, 'Käse');
      expect(itemsApi.lastAddedNote, isNull);
      expect(itemsApi.lastAddedAmount, '1');
      expect(itemsApi.lastAddedUnit, 'PIECE');
    });

    testWidgets('keyboardSubmitAddsAnUnknownNameAsOnePiece', (tester) async {
      await tester.pumpWidget(buildSubject());
      await cubit.bootstrap();
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('fast-add-field')));
      await tester.enterText(find.byKey(const Key('fast-add-field')), 'Käse');
      await tester.testTextInput.receiveAction(TextInputAction.done);
      await tester.pumpAndSettle();

      expect(itemsApi.lastAddedName, 'Käse');
      expect(itemsApi.lastAddedAmount, '1');
      expect(itemsApi.lastAddedUnit, 'PIECE');
    });

    Future<void> typeIntoTheField(WidgetTester tester, String text) async {
      await tester.tap(find.byKey(const Key('fast-add-field')));
      await tester.enterText(find.byKey(const Key('fast-add-field')), text);
      await tester.pumpAndSettle();
    }

    testWidgets('theAddAsNewRowPreviewsTheParsedNameAndQuantity', (tester) async {
      await tester.pumpWidget(buildSubject());
      await cubit.bootstrap();
      await tester.pumpAndSettle();

      await typeIntoTheField(tester, '0,5 l Milch');

      expect(find.text('„Milch“ als neuen Artikel hinzufügen'), findsOneWidget);
      expect(find.byKey(const Key('fast-add-new-row-quantity')), findsOneWidget);
      expect(find.text('0,5 l'), findsOneWidget);
    });

    testWidgets('theAddAsNewRowPreviewsTheRememberedUnitForATypedQuantity', (tester) async {
      itemSuggestionsApi.suggestionsToReturn = const [
        ItemSuggestion(name: 'Milch', note: null, amount: '2', unit: 'LITRE'),
      ];
      await tester.pumpWidget(buildSubject());
      await cubit.bootstrap();
      await tester.pumpAndSettle();

      await typeIntoTheField(tester, '5 Milch');

      expect(
        find.descendant(of: find.byKey(const Key('fast-add-new-row')), matching: find.text('5 l')),
        findsOneWidget,
      );
    });

    testWidgets('keyboardSubmitSendsTheParsedAmountAndUnit', (tester) async {
      await tester.pumpWidget(buildSubject());
      await cubit.bootstrap();
      await tester.pumpAndSettle();

      await typeIntoTheField(tester, '500 g Mehl');
      await tester.testTextInput.receiveAction(TextInputAction.done);
      await tester.pumpAndSettle();

      expect(itemsApi.lastAddedName, 'Mehl');
      expect(itemsApi.lastAddedAmount, '500');
      expect(itemsApi.lastAddedUnit, 'GRAM');
    });

    testWidgets('typingAQuantityStillMatchesSuggestionsByTheNamePart', (tester) async {
      itemSuggestionsApi.suggestionsToReturn = const [
        ItemSuggestion(name: 'Milch', note: null, amount: '2', unit: 'LITRE'),
        ItemSuggestion(name: 'Brot', note: null, amount: '1', unit: 'PACK'),
      ];
      await tester.pumpWidget(buildSubject());
      await cubit.bootstrap();
      await tester.pumpAndSettle();

      await typeIntoTheField(tester, '5 Mil');

      expect(find.byKey(const Key('fast-add-suggestion-milch')), findsOneWidget);
      expect(find.byKey(const Key('fast-add-suggestion-brot')), findsNothing);
    });

    testWidgets('tappingASuggestionWhileAQuantityIsTypedUsesTheTypedQuantity', (tester) async {
      itemSuggestionsApi.suggestionsToReturn = const [
        ItemSuggestion(name: 'Milch', note: 'Bio', amount: '2', unit: 'LITRE'),
      ];
      await tester.pumpWidget(buildSubject());
      await cubit.bootstrap();
      await tester.pumpAndSettle();

      await typeIntoTheField(tester, '5 Mil');
      await tester.tap(find.byKey(const Key('fast-add-suggestion-milch')));
      await tester.pumpAndSettle();

      expect(itemsApi.lastAddedName, 'Milch');
      expect(itemsApi.lastAddedAmount, '5');
      expect(itemsApi.lastAddedUnit, 'LITRE');
      expect(itemsApi.lastAddedNote, 'Bio');
    });

    Future<void> addDuplicateMilch(WidgetTester tester) async {
      itemsApi.addError = const AppException(AppError(code: 'item.duplicate', message: 'debug'));
      await tester.pumpWidget(buildSubject());
      await cubit.bootstrap();
      await tester.pumpAndSettle();
      await typeIntoTheField(tester, 'Milch');
      await tester.testTextInput.receiveAction(TextInputAction.done);
      await tester.pumpAndSettle();
    }

    testWidgets('aDuplicateRejectionShowsTheErrorTextDirectlyAboveTheTextField', (tester) async {
      await addDuplicateMilch(tester);

      final errorText = tester.getRect(find.byKey(const Key('item-list-action-error')));
      final textField = tester.getRect(find.byKey(const Key('fast-add-field')));
      expect(errorText.bottom, lessThanOrEqualTo(textField.top));
      expect(textField.top - errorText.bottom, lessThan(48), reason: 'the message hugs the field');
    });

    testWidgets('theErrorTextHugsTheFieldWithRoomUnderTheSuggestions', (tester) async {
      await addDuplicateMilch(tester);

      final panelBottom = tester.getRect(find.byKey(const Key('fast-add-panel-surface'))).bottom;
      final errorText = tester.getRect(find.byKey(const Key('item-list-action-error')));
      final fieldTop = tester.getRect(find.byKey(const Key('fast-add-field'))).top;

      final gapAboveTheMessage = errorText.top - panelBottom;
      final gapBelowTheMessage = fieldTop - errorText.bottom;
      expect(gapAboveTheMessage, greaterThan(8), reason: 'breathing room under the suggestions');
      expect(gapBelowTheMessage, closeTo(6, 0.5), reason: 'the message belongs to the input below it');
    });

    testWidgets('editingTheTypedTextDismissesTheErrorText', (tester) async {
      await addDuplicateMilch(tester);

      await tester.enterText(find.byKey(const Key('fast-add-field')), 'Milch 2');
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('item-list-action-error')), findsNothing);
    });

    testWidgets('movingTheCursorWithoutEditingKeepsTheErrorText', (tester) async {
      await addDuplicateMilch(tester);

      tester.testTextInput.updateEditingValue(const TextEditingValue(
        text: 'Milch',
        selection: TextSelection.collapsed(offset: 0),
      ));
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('item-list-action-error')), findsOneWidget);
    });

    testWidgets('thePanelFloatsOverTheListWithoutPushingTheFieldUp', (tester) async {
      itemSuggestionsApi.suggestionsToReturn = const [
        ItemSuggestion(name: 'Milch', note: null, amount: '2', unit: 'LITRE'),
        ItemSuggestion(name: 'Milchreis', note: null, amount: '1', unit: 'PACK'),
      ];
      await tester.pumpWidget(buildSubject());
      await cubit.bootstrap();
      await tester.pumpAndSettle();
      final fieldTopBeforeTyping = tester.getTopLeft(find.byKey(const Key('fast-add-field'))).dy;
      final listAreaHeightBeforeTyping = tester.getSize(find.byKey(const Key('list-area'))).height;

      await typeIntoTheField(tester, 'Mil');

      expect(tester.getTopLeft(find.byKey(const Key('fast-add-field'))).dy, fieldTopBeforeTyping);
      expect(tester.getSize(find.byKey(const Key('list-area'))).height, listAreaHeightBeforeTyping);
      final panel = tester.getRect(find.byKey(const Key('fast-add-panel-surface')));
      expect(panel.bottom, lessThanOrEqualTo(fieldTopBeforeTyping));
    });

    testWidgets('thePanelIsAsTallAsItsRowsAndSitsOnTheFooter', (tester) async {
      itemSuggestionsApi.suggestionsToReturn = const [
        ItemSuggestion(name: 'Milch', note: null, amount: '2', unit: 'LITRE'),
      ];
      await tester.pumpWidget(buildSubject());
      await cubit.bootstrap();
      await tester.pumpAndSettle();
      final fieldTop = tester.getTopLeft(find.byKey(const Key('fast-add-field'))).dy;

      await typeIntoTheField(tester, 'Mil');

      final panel = tester.getRect(find.byKey(const Key('fast-add-panel-surface')));
      final rowsHeight = tester.getSize(find.byKey(const Key('fast-add-suggestion-milch'))).height +
          tester.getSize(find.byKey(const Key('fast-add-new-row'))).height;
      expect(panel.height, lessThan(rowsHeight + 24), reason: 'one suggestion plus the add row, not the screen');
      expect(fieldTop - panel.bottom, lessThan(80), reason: 'the panel sits right above the footer');
    });

    testWidgets('thePanelOverlapsTheTopOfTheFooterByAFewPixels', (tester) async {
      itemSuggestionsApi.suggestionsToReturn = const [
        ItemSuggestion(name: 'Milch', note: null, amount: '2', unit: 'LITRE'),
      ];
      await tester.pumpWidget(buildSubject());
      await cubit.bootstrap();
      await tester.pumpAndSettle();
      final footerTop = tester.getTopLeft(find.byKey(const Key('fast-add-field'))).dy - SgartShapes.cardPadding;

      await typeIntoTheField(tester, 'Mil');

      final panel = tester.getRect(find.byKey(const Key('fast-add-panel-surface')));
      expect(panel.bottom - footerTop, closeTo(6, 0.5), reason: 'it dips 6px into the footer');
    });

    testWidgets('suggestionRowsSitInsideARoundedHairlineBorderedSurface', (tester) async {
      itemSuggestionsApi.suggestionsToReturn = const [
        ItemSuggestion(name: 'Milch', note: null, amount: '2', unit: 'LITRE'),
      ];
      await tester.pumpWidget(buildSubject());
      await cubit.bootstrap();
      await tester.pumpAndSettle();

      await typeIntoTheField(tester, 'Mil');

      final panel = tester.widget<Material>(find.byKey(const Key('fast-add-panel-surface')));
      final shape = panel.shape! as RoundedRectangleBorder;
      expect(panel.color, SgartColors.light().surface);
      expect(shape.borderRadius, SgartShapes.card);
      expect(shape.side.color, SgartColors.light().border);
      expect(shape.side.width, SgartShapes.hairline);
      expect(
        find.descendant(
          of: find.byKey(const Key('fast-add-panel-surface')),
          matching: find.byKey(const Key('fast-add-suggestion-milch')),
        ),
        findsOneWidget,
      );
    });

    testWidgets('theAddAsNewRowIsTintedInThePrimaryHueAndLedByAPlusIcon', (tester) async {
      await tester.pumpWidget(buildSubject());
      await cubit.bootstrap();
      await tester.pumpAndSettle();

      await typeIntoTheField(tester, 'Käse');

      final row = tester.widget<ListTile>(find.byKey(const Key('fast-add-new-row')));
      expect(row.tileColor, SgartColors.light().primary.withValues(alpha: SgartColors.tintAlpha));
      expect(
        find.descendant(of: find.byKey(const Key('fast-add-new-row')), matching: find.byIcon(Icons.add)),
        findsOneWidget,
      );
    });

    testWidgets('suggestionRowsMeetTheMinimumTapTargetHeight', (tester) async {
      itemSuggestionsApi.suggestionsToReturn = const [
        ItemSuggestion(name: 'Milch', note: null, amount: '2', unit: 'LITRE'),
      ];
      await tester.pumpWidget(buildSubject());
      await cubit.bootstrap();
      await tester.pumpAndSettle();

      await typeIntoTheField(tester, 'Mil');

      expect(
        tester.getSize(find.byKey(const Key('fast-add-suggestion-milch'))).height,
        greaterThanOrEqualTo(SgartShapes.minTapTarget),
      );
      expect(
        tester.getSize(find.byKey(const Key('fast-add-new-row'))).height,
        greaterThanOrEqualTo(SgartShapes.minTapTarget),
      );
    });

    testWidgets('emptyOrLoadingSuggestionsStillAllowAddAsNew', (tester) async {
      itemSuggestionsApi.listError = const AppException(AppError(code: 'network.unreachable', message: 'debug'));
      await tester.pumpWidget(buildSubject());
      await cubit.bootstrap();
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('fast-add-field')));
      await tester.enterText(find.byKey(const Key('fast-add-field')), 'Milch');
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('fast-add-new-row')), findsOneWidget);
      await tester.tap(find.byKey(const Key('fast-add-new-row')));
      await tester.pumpAndSettle();

      expect(itemsApi.lastAddedName, 'Milch');
    });

    testWidgets('isSubmittingDisablesReentrantSubmit', (tester) async {
      itemsApi.itemsToReturn = const [
        Item(itemId: 'i1', name: 'Existing', note: null, amount: '1', unit: 'PIECE'),
      ];
      await tester.pumpWidget(buildSubject());
      await cubit.bootstrap();
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('fast-add-field')));
      await tester.enterText(find.byKey(const Key('fast-add-field')), 'Milch');
      await tester.pumpAndSettle();
      // Fire two taps back-to-back without settling in between — the cubit's own isSubmitting guard
      // (mirrors the old add-button's behaviour) ensures only the first is honoured.
      await tester.tap(find.byKey(const Key('fast-add-new-row')));
      await tester.tap(find.byKey(const Key('fast-add-new-row')));
      await tester.pumpAndSettle();

      expect(itemsApi.addCallCount, 1);
    });

    testWidgets('aSuccessfulAddClearsTheFieldButKeepsTheFocusForTheNextArticle', (tester) async {
      await tester.pumpWidget(buildSubject());
      await cubit.bootstrap();
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('fast-add-field')));
      await tester.enterText(find.byKey(const Key('fast-add-field')), 'Käse');
      await tester.testTextInput.receiveAction(TextInputAction.done);
      await tester.pumpAndSettle();

      // Fast capture is the hero (Cl. 3): the keyboard stays up so the next article is one keystroke
      // away, and the panel is gone simply because the text is.
      final field = tester.widget<TextField>(find.byKey(const Key('fast-add-field')));
      expect(field.controller!.text, isEmpty);
      expect(field.focusNode!.hasFocus, isTrue);
      expect(find.byKey(const Key('fast-add-new-row')), findsNothing);
    });

    testWidgets('aRejectedAddKeepsTheTypedTextAndTheFocusSoTheMemberCanRetry', (tester) async {
      itemsApi.addError = const AppException(AppError(code: 'item.duplicate', message: 'debug'));
      await tester.pumpWidget(buildSubject());
      await cubit.bootstrap();
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('fast-add-field')));
      await tester.enterText(find.byKey(const Key('fast-add-field')), 'Milch');
      await tester.testTextInput.receiveAction(TextInputAction.done);
      await tester.pumpAndSettle();

      final field = tester.widget<TextField>(find.byKey(const Key('fast-add-field')));
      expect(field.controller!.text, 'Milch');
      expect(field.focusNode!.hasFocus, isTrue);
    });

    testWidgets('thePanelCapsItsRowsAndNeverTruncatesTheExactMatch', (tester) async {
      // Eight names share the "Milch" prefix — two more than the panel shows. The exact match is the
      // shortest of them and so sorts first, which is why capping the tail is safe.
      itemSuggestionsApi.suggestionsToReturn = const [
        ItemSuggestion(name: 'Milchbrötchen', note: null, amount: '1', unit: 'PACK'),
        ItemSuggestion(name: 'Milcheis', note: null, amount: '1', unit: 'PACK'),
        ItemSuggestion(name: 'Milchkaffee', note: null, amount: '1', unit: 'PIECE'),
        ItemSuggestion(name: 'Milchpulver', note: null, amount: '1', unit: 'PACK'),
        ItemSuggestion(name: 'Milchreis', note: null, amount: '1', unit: 'PACK'),
        ItemSuggestion(name: 'Milchschokolade', note: null, amount: '1', unit: 'PACK'),
        ItemSuggestion(name: 'Milchshake', note: null, amount: '1', unit: 'PIECE'),
        ItemSuggestion(name: 'Milch', note: 'Bio', amount: '2', unit: 'LITRE'),
      ];
      await tester.pumpWidget(buildSubject());
      await cubit.bootstrap();
      await tester.pumpAndSettle();

      await tester.tap(find.byKey(const Key('fast-add-field')));
      await tester.enterText(find.byKey(const Key('fast-add-field')), 'Milch');
      await tester.pumpAndSettle();

      expect(find.byKey(const Key('fast-add-suggestion-milch')), findsOneWidget);
      expect(find.byKey(const Key('fast-add-suggestion-milchshake')), findsNothing);
    });
  });
}
