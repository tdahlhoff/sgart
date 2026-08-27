import 'package:collection/collection.dart';

import '../../../../shared/errors/app_error.dart';
import '../../data/shopping_list_summary.dart';

enum ShoppingListsStatus { loading, ready, failure }

/// Which body the Listen overview renders (Story 2.2, AC1/AC2): the Open lists (default) or the
/// read-only Done archive.
enum ListFilter { open, done }

/// Load status of the Done archive, kept separate from [ShoppingListsStatus] so a failed archive
/// load never tears down the Open view (mirrors how `actionError` is kept separate from
/// `loadError`). The archive is lazily loaded on first `done` selection and cached.
enum ArchiveStatus { idle, loading, ready, failure }

/// State of [ShoppingListsCubit] (Story 2.1; the Offen/Erledigt filter + archive, Story 2.2).
/// [loading]/[failure] cover the initial load of the household's Open lists; once [ready] it
/// carries the `lists` in creation order (the AC2 ordinal source, and the Offen body), the current
/// [filter] (default [ListFilter.open]), the Done archive (`doneLists` + `archiveStatus` +
/// `archiveError`), the `isSubmitting` flag for an in-flight create/rename, and `actionError` for a
/// create/rename rejection shown inline — kept separate from `loadError` so a rejected action never
/// tears down the screen.
class ShoppingListsState {
  const ShoppingListsState._(
    this.status, {
    this.lists = const [],
    this.filter = ListFilter.open,
    this.doneLists = const [],
    this.archiveStatus = ArchiveStatus.idle,
    this.archiveError,
    this.isSubmitting = false,
    this.loadError,
    this.actionError,
  });

  const ShoppingListsState.loading() : this._(ShoppingListsStatus.loading);

  const ShoppingListsState.failure(AppError error) : this._(ShoppingListsStatus.failure, loadError: error);

  const ShoppingListsState.ready({
    required List<ShoppingListSummary> lists,
    ListFilter filter = ListFilter.open,
    List<ShoppingListSummary> doneLists = const [],
    ArchiveStatus archiveStatus = ArchiveStatus.idle,
    AppError? archiveError,
    bool isSubmitting = false,
    AppError? actionError,
  }) : this._(
          ShoppingListsStatus.ready,
          lists: lists,
          filter: filter,
          doneLists: doneLists,
          archiveStatus: archiveStatus,
          archiveError: archiveError,
          isSubmitting: isSubmitting,
          actionError: actionError,
        );

  final ShoppingListsStatus status;
  final List<ShoppingListSummary> lists;
  final ListFilter filter;
  final List<ShoppingListSummary> doneLists;
  final ArchiveStatus archiveStatus;
  final AppError? archiveError;
  final bool isSubmitting;
  final AppError? loadError;
  final AppError? actionError;

  ShoppingListsState copyWith({
    List<ShoppingListSummary>? lists,
    ListFilter? filter,
    List<ShoppingListSummary>? doneLists,
    ArchiveStatus? archiveStatus,
    AppError? archiveError,
    bool clearArchiveError = false,
    bool? isSubmitting,
    AppError? actionError,
    bool clearActionError = false,
  }) {
    return ShoppingListsState.ready(
      lists: lists ?? this.lists,
      filter: filter ?? this.filter,
      doneLists: doneLists ?? this.doneLists,
      archiveStatus: archiveStatus ?? this.archiveStatus,
      archiveError: clearArchiveError ? null : (archiveError ?? this.archiveError),
      isSubmitting: isSubmitting ?? this.isSubmitting,
      actionError: clearActionError ? null : (actionError ?? this.actionError),
    );
  }

  @override
  bool operator ==(Object other) =>
      other is ShoppingListsState &&
      other.status == status &&
      const ListEquality<ShoppingListSummary>().equals(other.lists, lists) &&
      other.filter == filter &&
      const ListEquality<ShoppingListSummary>().equals(other.doneLists, doneLists) &&
      other.archiveStatus == archiveStatus &&
      other.archiveError == archiveError &&
      other.isSubmitting == isSubmitting &&
      other.loadError == loadError &&
      other.actionError == actionError;

  @override
  int get hashCode => Object.hash(
        status,
        const ListEquality<ShoppingListSummary>().hash(lists),
        filter,
        const ListEquality<ShoppingListSummary>().hash(doneLists),
        archiveStatus,
        archiveError,
        isSubmitting,
        loadError,
        actionError,
      );
}
