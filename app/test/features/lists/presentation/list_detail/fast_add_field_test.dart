import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/features/lists/data/item.dart';
import 'package:sgart/features/lists/data/item_suggestion.dart';
import 'package:sgart/features/lists/presentation/list_detail/fast_add_field.dart';
import 'package:sgart/features/lists/presentation/list_detail/list_detail_cubit.dart';
import 'package:sgart/shared/errors/app_error.dart';
import 'package:sgart/shared/http/app_exception.dart';

import '../../../../support/fake_item_suggestions_api.dart';
import '../../../../support/fake_items_dependencies.dart';
import '../../../../support/widget_test_harness.dart';

/// Widget tests for the persistent fast-add field (Story 2.5, AC2/AC3/AC4) in isolation — mirrors
/// the list_detail_page_test.dart route-level coverage but drives the widget directly.
void main() {
  group('FastAddField', () {
    late FakeItemsApi itemsApi;
    late FakeItemSuggestionsApi itemSuggestionsApi;
    late ListDetailCubit cubit;

    setUp(() {
      itemsApi = FakeItemsApi();
      itemSuggestionsApi = FakeItemSuggestionsApi();
    });

    tearDown(() => cubit.close());

    Widget buildSubject() {
      cubit = ListDetailCubit(
        itemsApi: itemsApi,
        itemSuggestionsApi: itemSuggestionsApi,
        householdId: 'household-1',
        listId: 'list-1',
        isReadOnly: false,
      );
      return wrapForTesting(
        BlocProvider<ListDetailCubit>.value(
          value: cubit,
          child: Scaffold(body: FastAddField(cubit: cubit)),
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

    testWidgets('theAddAsNewRowCallsAddItemWithStory23Defaults', (tester) async {
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

    testWidgets('keyboardSubmitCallsAddItemWithStory23Defaults', (tester) async {
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
