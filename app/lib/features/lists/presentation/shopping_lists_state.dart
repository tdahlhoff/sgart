import 'package:collection/collection.dart';

import '../../../shared/errors/app_error.dart';
import '../data/shopping_list_summary.dart';

enum ShoppingListsStatus { loading, ready, failure }

/// State of [ShoppingListsCubit] (Story 2.1). [loading]/[failure] cover the initial load of the
/// household's Open lists; once [ready] it carries the `lists` in creation order (the AC2 ordinal
/// source), the `isSubmitting` flag for an in-flight create/rename, and `actionError` for a
/// create/rename rejection shown inline — kept separate from `loadError` so a rejected action never
/// tears down the screen.
class ShoppingListsState {
  const ShoppingListsState._(
    this.status, {
    this.lists = const [],
    this.isSubmitting = false,
    this.loadError,
    this.actionError,
  });

  const ShoppingListsState.loading() : this._(ShoppingListsStatus.loading);

  const ShoppingListsState.failure(AppError error) : this._(ShoppingListsStatus.failure, loadError: error);

  const ShoppingListsState.ready({
    required List<ShoppingListSummary> lists,
    bool isSubmitting = false,
    AppError? actionError,
  }) : this._(ShoppingListsStatus.ready, lists: lists, isSubmitting: isSubmitting, actionError: actionError);

  final ShoppingListsStatus status;
  final List<ShoppingListSummary> lists;
  final bool isSubmitting;
  final AppError? loadError;
  final AppError? actionError;

  ShoppingListsState copyWith({
    List<ShoppingListSummary>? lists,
    bool? isSubmitting,
    AppError? actionError,
    bool clearActionError = false,
  }) {
    return ShoppingListsState.ready(
      lists: lists ?? this.lists,
      isSubmitting: isSubmitting ?? this.isSubmitting,
      actionError: clearActionError ? null : (actionError ?? this.actionError),
    );
  }

  @override
  bool operator ==(Object other) =>
      other is ShoppingListsState &&
      other.status == status &&
      const ListEquality<ShoppingListSummary>().equals(other.lists, lists) &&
      other.isSubmitting == isSubmitting &&
      other.loadError == loadError &&
      other.actionError == actionError;

  @override
  int get hashCode => Object.hash(
        status,
        const ListEquality<ShoppingListSummary>().hash(lists),
        isSubmitting,
        loadError,
        actionError,
      );
}
