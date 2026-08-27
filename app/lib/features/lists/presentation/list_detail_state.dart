import 'package:collection/collection.dart';

import '../../../shared/errors/app_error.dart';
import '../data/item.dart';

enum ListDetailStatus { loading, ready, failure }

/// State of [ListDetailCubit] (Story 2.3, AC6). [loading]/[failure] cover the initial load of the
/// list's items; once [ready] it carries the `items` in creation order, `isReadOnly` (a Done list —
/// no add/edit/remove affordances), the `isSubmitting` flag for an in-flight add/update/remove, and
/// `actionError` for a rejection shown inline — kept separate from `loadError` so a rejected action
/// never tears down the screen.
class ListDetailState {
  const ListDetailState._(
    this.status, {
    this.items = const [],
    this.isReadOnly = false,
    this.isSubmitting = false,
    this.loadError,
    this.actionError,
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
  }) : this._(
          ListDetailStatus.ready,
          items: items,
          isReadOnly: isReadOnly,
          isSubmitting: isSubmitting,
          actionError: actionError,
        );

  final ListDetailStatus status;
  final List<Item> items;
  final bool isReadOnly;
  final bool isSubmitting;
  final AppError? loadError;
  final AppError? actionError;

  ListDetailState copyWith({
    List<Item>? items,
    bool? isSubmitting,
    AppError? actionError,
    bool clearActionError = false,
  }) {
    return ListDetailState.ready(
      items: items ?? this.items,
      isReadOnly: isReadOnly,
      isSubmitting: isSubmitting ?? this.isSubmitting,
      actionError: clearActionError ? null : (actionError ?? this.actionError),
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
      other.actionError == actionError;

  @override
  int get hashCode => Object.hash(
        status,
        const ListEquality<Item>().hash(items),
        isReadOnly,
        isSubmitting,
        loadError,
        actionError,
      );
}
