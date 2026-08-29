import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../../l10n/gen/app_localizations.dart';
import '../../../../shared/errors/error_message_resolver.dart';
import '../../../../shared/widgets/sgart_button.dart';
import '../../../../shared/widgets/status_label.dart';
import '../../../../theme/tokens/sgart_shapes.dart';
import '../../data/shopping_list_summary.dart';
import '../../../trips/presentation/trip_screen.dart';
import '../list_detail/list_detail_page.dart';
import 'create_list_dialog.dart';
import 'rename_list_dialog.dart';
import 'shopping_lists_cubit.dart';
import 'shopping_lists_state.dart';

/// The Listen overview (Story 2.1 AC1–AC3, Clarification 2; Story 2.2 AC1/AC2): a segmented
/// Offen/Erledigt filter at the top switches the body between the household's Open lists (each
/// showing its name or the derived „Liste N", AC2, a status label, a per-row rename affordance, and
/// a „+ Neue Liste" create action) and the read-only Done archive (no create, no rename, no item
/// actions). Reads its [ShoppingListsCubit] from the enclosing provider (scoped to the active
/// household by the shell). Item counts/progress are out of scope (2.3/Epic 3).
class ListsView extends StatelessWidget {
  const ListsView({super.key});

  @override
  Widget build(BuildContext context) {
    return BlocBuilder<ShoppingListsCubit, ShoppingListsState>(
      builder: (context, state) {
        return switch (state.status) {
          ShoppingListsStatus.loading =>
            const Center(child: CircularProgressIndicator(key: Key('lists-loading'))),
          ShoppingListsStatus.failure => const _FailureBody(),
          ShoppingListsStatus.ready => _ReadyBody(state: state),
        };
      },
    );
  }
}

class _ReadyBody extends StatelessWidget {
  const _ReadyBody({required this.state});

  final ShoppingListsState state;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);
    final cubit = context.read<ShoppingListsCubit>();

    return SingleChildScrollView(
      padding: const EdgeInsets.all(SgartShapes.cardPadding),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SegmentedButton<ListFilter>(
            key: const Key('lists-filter-segmented-button'),
            segments: [
              ButtonSegment(
                value: ListFilter.open,
                label: Text(localizations.listsFilterOpen),
              ),
              ButtonSegment(
                value: ListFilter.done,
                label: Text(localizations.listsFilterDone),
              ),
            ],
            selected: {state.filter},
            onSelectionChanged: (selection) => cubit.selectFilter(selection.first),
          ),
          const SizedBox(height: SgartShapes.space4),
          switch (state.filter) {
            ListFilter.open => _OpenListsBody(state: state, cubit: cubit),
            ListFilter.done => _DoneArchiveBody(state: state),
          },
        ],
      ),
    );
  }
}

class _OpenListsBody extends StatelessWidget {
  const _OpenListsBody({required this.state, required this.cubit});

  final ShoppingListsState state;
  final ShoppingListsCubit cubit;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (state.lists.isEmpty)
          Text(localizations.listsEmptyState, key: const Key('lists-empty-state'))
        else
          for (final (index, list) in state.lists.indexed)
            _ListRow(
              list: list,
              // The AC2 ordinal is the 1-based position in the creation-ordered array the query
              // already returns — counting named lists too (derivation lives on the client).
              orderIndex: index + 1,
              onRename: () => showRenameListSheet(
                context,
                cubit,
                listId: list.listId,
                currentName: list.name ?? localizations.listsDefaultName(index + 1),
              ),
              onOpen: () {
                final displayName = list.name ?? localizations.listsDefaultName(index + 1);
                // An „Im Einkauf" row opens the trip screen directly (Story 3.2, AC4) — the
                // list-detail's own item edits are off-trip only (Story 3.1, AC6). A still-Open row
                // opens list detail, which may itself transition In-Trip mid-session ("Einkauf
                // starten") and navigate on from there (Story 3.2, Cl. 3).
                final activeTripId = list.activeTripId;
                if (list.status == 'IN_TRIP' && activeTripId != null) {
                  TripScreen.push(context, householdId: cubit.householdId, listId: list.listId, listTitle: displayName);
                  return;
                }
                ListDetailPage.push(
                  context,
                  householdId: cubit.householdId,
                  listId: list.listId,
                  title: displayName,
                  isReadOnly: list.status != 'OPEN',
                  // On return from an editable (Open) list, refresh the overview so each row's
                  // itemCount reflects any add/remove the user just made (the count is a server-side
                  // COUNT, not mutated by the detail cubit — otherwise it reads stale), and so a
                  // just-started trip's In-Trip label appears (Story 3.1, AC5).
                  onEditableReturn: cubit.refresh,
                );
              },
            ),
        if (state.actionError != null) ...[
          const SizedBox(height: SgartShapes.space4),
          Text(
            localizedMessageForErrorCode(localizations, state.actionError!.code),
            key: const Key('lists-action-error'),
          ),
        ],
        const SizedBox(height: SgartShapes.space4),
        SgartButton(
          key: const Key('lists-create-button'),
          label: localizations.listsCreateAction,
          onPressed: state.isSubmitting ? null : () => showCreateListSheet(context, cubit),
        ),
      ],
    );
  }
}

class _ListRow extends StatelessWidget {
  const _ListRow({required this.list, required this.orderIndex, required this.onRename, required this.onOpen});

  final ShoppingListSummary list;
  final int orderIndex;
  final VoidCallback onRename;
  final VoidCallback onOpen;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);
    final displayName = list.name ?? localizations.listsDefaultName(orderIndex);

    return ListTile(
      key: Key('list-row-${list.listId}'),
      contentPadding: EdgeInsets.zero,
      onTap: onOpen,
      title: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(displayName),
          const SizedBox(height: SgartShapes.spaceHalfUnit),
          Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              StatusLabel(
                key: Key('list-status-${list.listId}'),
                // „Offen" now returns both Open and In-Trip lists (Story 3.1, AC5) — the row shows
                // the matching label rather than assuming Open.
                text: list.status == 'IN_TRIP' ? localizations.listStatusInTrip : localizations.listStatusOpen,
              ),
              const SizedBox(width: SgartShapes.space2),
              Text(
                localizations.listsItemCount(list.itemCount),
                key: Key('list-item-count-${list.listId}'),
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ],
          ),
        ],
      ),
      trailing: IconButton(
        key: Key('list-rename-button-${list.listId}'),
        icon: const Icon(Icons.edit_outlined),
        tooltip: localizations.listsRenameAction,
        onPressed: onRename,
      ),
    );
  }
}

class _DoneArchiveBody extends StatelessWidget {
  const _DoneArchiveBody({required this.state});

  final ShoppingListsState state;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return switch (state.archiveStatus) {
      ArchiveStatus.idle || ArchiveStatus.loading =>
        const Center(child: CircularProgressIndicator(key: Key('lists-archive-loading'))),
      ArchiveStatus.failure => Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              localizedMessageForErrorCode(localizations, state.archiveError!.code),
              key: const Key('lists-archive-error'),
            ),
            const SizedBox(height: SgartShapes.space4),
            SgartButton(
              key: const Key('lists-archive-retry-button'),
              label: localizations.householdsRetryButtonLabel,
              onPressed: () => context.read<ShoppingListsCubit>().retryArchive(),
            ),
          ],
        ),
      ArchiveStatus.ready => Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (state.doneLists.isEmpty)
              Text(localizations.listsArchiveEmptyState, key: const Key('lists-archive-empty-state'))
            else
              for (final list in state.doneLists)
                _ArchiveRow(
                  list: list,
                  onOpen: () => ListDetailPage.push(
                    context,
                    householdId: context.read<ShoppingListsCubit>().householdId,
                    listId: list.listId,
                    title: list.name ?? localizations.listsArchiveUnnamedFallback,
                    isReadOnly: true,
                    // No onEditableReturn: a Done list is immutable, and push never fires the
                    // callback for a read-only list anyway (a refresh here would reset the overview
                    // to the Open filter, snapping the user off the Done archive they were browsing).
                  ),
                ),
          ],
        ),
    };
  }
}

class _ArchiveRow extends StatelessWidget {
  const _ArchiveRow({required this.list, required this.onOpen});

  final ShoppingListSummary list;
  final VoidCallback onOpen;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);
    // A Done list has no ordinal in the "Liste N" sequence (2.1's ordinal counts Open lists only) —
    // the simplest correct fallback for an unnamed archived row (Epic 3 finalizes this labeling).
    final displayName = list.name ?? localizations.listsArchiveUnnamedFallback;

    return ListTile(
      key: Key('list-archive-row-${list.listId}'),
      contentPadding: EdgeInsets.zero,
      onTap: onOpen,
      title: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(displayName),
          const SizedBox(height: SgartShapes.spaceHalfUnit),
          StatusLabel(
            key: Key('list-status-${list.listId}'),
            text: localizations.listStatusDone,
          ),
        ],
      ),
    );
  }
}

class _FailureBody extends StatelessWidget {
  const _FailureBody();

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return Center(
      child: Padding(
        padding: const EdgeInsets.all(SgartShapes.cardPadding),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(localizations.errorGenericFallback, key: const Key('lists-load-error')),
            const SizedBox(height: SgartShapes.space4),
            SgartButton(
              key: const Key('lists-retry-button'),
              label: localizations.householdsRetryButtonLabel,
              onPressed: () => context.read<ShoppingListsCubit>().refresh(),
            ),
          ],
        ),
      ),
    );
  }
}
