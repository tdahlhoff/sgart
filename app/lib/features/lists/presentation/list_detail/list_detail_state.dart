import 'package:collection/collection.dart';

import '../../../../shared/errors/app_error.dart';
import '../../../stores/data/store_summary.dart';
import '../../data/item.dart';
import '../../data/item_suggestion.dart';

enum ListDetailStatus { loading, ready, failure }

/// State of [ListDetailCubit] (Story 2.3, AC6; Story 2.5, AC1; Story 2.6, AC1/AC4/AC6).
/// [loading]/[failure] cover the initial load of the list's items; once [ready] it carries the
/// `items` in creation order, `isReadOnly` (a Done list — no add/edit/remove/assign affordances,
/// and no suggestions/stores, AC5), the `isSubmitting` flag for an in-flight add/update/remove/
/// assign, `actionError` for a rejection shown inline — kept separate from `loadError` so a
/// rejected action never tears down the screen — `suggestions`, the household's in-memory
/// suggestion cache the fast-add field filters, and `stores`, the household's active stores the
/// store picker offers (both empty on a read-only list or while their load is still in
/// flight/failed).
class ListDetailState {
  const ListDetailState._(
    this.status, {
    this.items = const [],
    this.isReadOnly = false,
    this.isSubmitting = false,
    this.loadError,
    this.actionError,
    this.suggestions = const [],
    this.stores = const [],
  });

  const ListDetailState.loading({required bool isReadOnly})
      : this._(ListDetailStatus.loading, isReadOnly: isReadOnly);

  const ListDetailState.failure(AppError error, {required bool isReadOnly})
      : this._(ListDetailStatus.failure, isReadOnly: isReadOnly, loadError: error);

  const ListDetailState.ready({
    required List<Item> items,
    required bool isReadOnly,
    bool isSubmitting = false,
    AppError? actionError,
    List<ItemSuggestion> suggestions = const [],
    List<StoreSummary> stores = const [],
  }) : this._(
          ListDetailStatus.ready,
          items: items,
          isReadOnly: isReadOnly,
          isSubmitting: isSubmitting,
          actionError: actionError,
          suggestions: suggestions,
          stores: stores,
        );

  final ListDetailStatus status;
  final List<Item> items;
  final bool isReadOnly;
  final bool isSubmitting;
  final AppError? loadError;
  final AppError? actionError;
  final List<ItemSuggestion> suggestions;
  final List<StoreSummary> stores;

  ListDetailState copyWith({
    List<Item>? items,
    bool? isSubmitting,
    AppError? actionError,
    bool clearActionError = false,
    List<ItemSuggestion>? suggestions,
    List<StoreSummary>? stores,
  }) {
    return ListDetailState.ready(
      items: items ?? this.items,
      isReadOnly: isReadOnly,
      isSubmitting: isSubmitting ?? this.isSubmitting,
      actionError: clearActionError ? null : (actionError ?? this.actionError),
      suggestions: suggestions ?? this.suggestions,
      stores: stores ?? this.stores,
    );
  }

  @override
  bool operator ==(Object other) =>
      other is ListDetailState &&
      other.status == status &&
      const ListEquality<Item>().equals(other.items, items) &&
      other.isReadOnly == isReadOnly &&
      other.isSubmitting == isSubmitting &&
      other.loadError == loadError &&
      other.actionError == actionError &&
      const ListEquality<ItemSuggestion>().equals(other.suggestions, suggestions) &&
      const ListEquality<StoreSummary>().equals(other.stores, stores);

  @override
  int get hashCode => Object.hash(
        status,
        const ListEquality<Item>().hash(items),
        isReadOnly,
        isSubmitting,
        loadError,
        actionError,
        const ListEquality<ItemSuggestion>().hash(suggestions),
        const ListEquality<StoreSummary>().hash(stores),
      );
}
