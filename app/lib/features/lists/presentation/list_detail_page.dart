import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/formatting/quantity_formatter.dart' as formatting;
import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/errors/error_message_resolver.dart';
import '../../../shared/widgets/sgart_app_bar.dart';
import '../../../shared/widgets/sgart_button.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import '../data/item.dart';
import '../data/shopping_lists_api.dart';
import 'fast_add_field.dart';
import 'item_form_sheet.dart';
import 'list_detail_cubit.dart';
import 'list_detail_state.dart';
import 'move_target_sheet.dart';

/// The list detail screen (Story 2.3, AC6; Story 2.5, AC2/AC3/AC4/AC5): the tapped list's items in
/// creation order, each row showing name · quantity · optional note, with an empty state and
/// per-row edit/remove affordances. An Open list's only add surface is the persistent fast-add
/// field at the bottom (AC4) — the Story 2.3 add button/sheet-add path is retired. A Done list opens
/// read-only — no fast-add field, no suggestion panel, no edit/remove affordances render at all
/// (AC5). Off-trip there is no check/uncheck/postpone (Epic 3) and no store assignment (Story 2.6).
/// Reads its [ListDetailCubit] from the enclosing provider (scoped to the list by the caller).
class ListDetailPage extends StatelessWidget {
  const ListDetailPage({super.key, required this.title});

  /// Already-derived display title (the list's name, or the „Liste N" fallback the overview
  /// computed) — this screen never re-derives the ordinal itself.
  final String title;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: SgartAppBar(title: title),
      body: Column(
        children: [
          Expanded(
            child: BlocBuilder<ListDetailCubit, ListDetailState>(
              builder: (context, state) {
                return switch (state.status) {
                  ListDetailStatus.loading =>
                    const Center(child: CircularProgressIndicator(key: Key('item-list-loading'))),
                  ListDetailStatus.failure => const _FailureBody(),
                  ListDetailStatus.ready => _ReadyBody(state: state),
                };
              },
            ),
          ),
          BlocBuilder<ListDetailCubit, ListDetailState>(
            builder: (context, state) {
              // The fast-add field is the only add surface on an Open list (AC4); a Done list shows
              // neither the field nor its suggestion panel (AC5).
              if (state.status != ListDetailStatus.ready || state.isReadOnly) {
                return const SizedBox.shrink();
              }
              return FastAddField(cubit: context.read<ListDetailCubit>());
            },
          ),
        ],
      ),
    );
  }
}

class _ReadyBody extends StatelessWidget {
  const _ReadyBody({required this.state});

  final ListDetailState state;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);
    final cubit = context.read<ListDetailCubit>();

    return SingleChildScrollView(
      padding: const EdgeInsets.all(SgartShapes.cardPadding),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          if (state.items.isEmpty)
            Text(localizations.itemsEmptyState, key: const Key('item-list-empty-state'))
          else
            for (final item in state.items)
              _ItemRow(
                item: item,
                isReadOnly: state.isReadOnly,
                onEdit: () => showItemFormSheet(context, cubit, existingItem: item),
                onRemove: () => cubit.removeItem(item.itemId),
                onMove: () => showMoveTargetSheet(
                  context,
                  cubit: cubit,
                  shoppingListsApi: context.read<ShoppingListsApi>(),
                  item: item,
                  householdId: cubit.householdId,
                  sourceListId: cubit.listId,
                ),
              ),
          if (state.actionError != null) ...[
            const SizedBox(height: SgartShapes.space4),
            Text(
              localizedMessageForErrorCode(localizations, state.actionError!.code),
              key: const Key('item-list-action-error'),
            ),
          ],
        ],
      ),
    );
  }
}

class _ItemRow extends StatelessWidget {
  const _ItemRow({
    required this.item,
    required this.isReadOnly,
    required this.onEdit,
    required this.onRemove,
    required this.onMove,
  });

  final Item item;
  final bool isReadOnly;
  final VoidCallback onEdit;
  final VoidCallback onRemove;
  final VoidCallback onMove;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);
    final quantityText = _formatQuantity(item, localizations);
    final subtitle = item.note == null ? quantityText : '$quantityText · ${item.note}';

    return ListTile(
      key: Key('item-row-${item.itemId}'),
      contentPadding: EdgeInsets.zero,
      title: Text(item.name),
      subtitle: Text(subtitle, key: Key('item-quantity-${item.itemId}')),
      trailing: isReadOnly
          ? null
          : Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                IconButton(
                  key: Key('item-edit-button-${item.itemId}'),
                  icon: const Icon(Icons.edit_outlined),
                  tooltip: localizations.itemEditAction,
                  onPressed: onEdit,
                ),
                IconButton(
                  key: Key('item-remove-button-${item.itemId}'),
                  icon: const Icon(Icons.delete_outline),
                  tooltip: localizations.itemRemoveAction,
                  onPressed: onRemove,
                ),
                IconButton(
                  key: Key('item-move-button-${item.itemId}'),
                  icon: const Icon(Icons.drive_file_move_outline),
                  tooltip: localizations.itemMoveAction,
                  onPressed: onMove,
                ),
              ],
            ),
    );
  }

  String _formatQuantity(Item item, AppLocalizations localizations) {
    final amount = double.tryParse(item.amount) ?? 0;
    final unit = formatting.unitFromServerName(item.unit) ?? formatting.Unit.piece;
    return const formatting.QuantityFormatter().format(amount, unit, localizations);
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
            Text(localizations.errorGenericFallback, key: const Key('item-list-load-error')),
            const SizedBox(height: SgartShapes.space4),
            SgartButton(
              key: const Key('item-list-retry-button'),
              label: localizations.householdsRetryButtonLabel,
              onPressed: () => context.read<ListDetailCubit>().refresh(),
            ),
          ],
        ),
      ),
    );
  }
}
