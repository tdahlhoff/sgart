import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/errors/error_message_resolver.dart';
import '../../../shared/widgets/sgart_button.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import '../data/shopping_list_summary.dart';
import 'create_list_dialog.dart';
import 'rename_list_dialog.dart';
import 'shopping_lists_cubit.dart';
import 'shopping_lists_state.dart';

/// The minimal lists surface (Story 2.1, AC1–AC3, Clarification 2): the household's Open lists,
/// each showing its name or the derived „Liste N" (AC2), a per-row rename affordance, a „+ Neue
/// Liste" create action, and an empty state (UX-DR13). Reads its [ShoppingListsCubit] from the
/// enclosing provider (scoped to the active household by the shell). The full Listen overview —
/// the Offen/Erledigt filter, item counts/progress, and the Done archive — is Story 2.2.
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
      ),
    );
  }
}

class _ListRow extends StatelessWidget {
  const _ListRow({required this.list, required this.orderIndex, required this.onRename});

  final ShoppingListSummary list;
  final int orderIndex;
  final VoidCallback onRename;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);
    final displayName = list.name ?? localizations.listsDefaultName(orderIndex);

    return ListTile(
      key: Key('list-row-${list.listId}'),
      contentPadding: EdgeInsets.zero,
      title: Text(displayName),
      trailing: IconButton(
        key: Key('list-rename-button-${list.listId}'),
        icon: const Icon(Icons.edit_outlined),
        tooltip: localizations.listsRenameAction,
        onPressed: onRename,
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
