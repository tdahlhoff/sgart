import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/formatting/quantity_formatter.dart' as formatting;
import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/errors/error_message_resolver.dart';
import '../../../shared/widgets/sgart_app_bar.dart';
import '../../../shared/widgets/sgart_button.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import '../data/item.dart';
import 'item_form_sheet.dart';
import 'list_detail_cubit.dart';
import 'list_detail_state.dart';

/// The list detail screen (Story 2.3, AC6): the tapped list's items in creation order, each row
/// showing name · quantity · optional note, with an empty state, an add affordance, and per-row
/// edit/remove affordances. A Done list opens read-only — no add/edit/remove affordances render at
/// all. Off-trip there is no check/uncheck/postpone (Epic 3) and no store assignment (Story 2.6).
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
      body: BlocBuilder<ListDetailCubit, ListDetailState>(
        builder: (context, state) {
          return switch (state.status) {
            ListDetailStatus.loading =>
              const Center(child: CircularProgressIndicator(key: Key('item-list-loading'))),
            ListDetailStatus.failure => const _FailureBody(),
            ListDetailStatus.ready => _ReadyBody(state: state),
          };
        },
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
              ),
          if (state.actionError != null) ...[
            const SizedBox(height: SgartShapes.space4),
            Text(
              localizedMessageForErrorCode(localizations, state.actionError!.code),
              key: const Key('item-list-action-error'),
            ),
          ],
          if (!state.isReadOnly) ...[
            const SizedBox(height: SgartShapes.space4),
            SgartButton(
              key: const Key('item-add-button'),
              label: localizations.itemAddAction,
              onPressed: state.isSubmitting ? null : () => showItemFormSheet(context, cubit),
            ),
          ],
        ],
      ),
    );
  }
}

class _ItemRow extends StatelessWidget {
  const _ItemRow({required this.item, required this.isReadOnly, required this.onEdit, required this.onRemove});

  final Item item;
  final bool isReadOnly;
  final VoidCallback onEdit;
  final VoidCallback onRemove;

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
              ],
            ),
    );
  }

  String _formatQuantity(Item item, AppLocalizations localizations) {
    final amount = double.tryParse(item.amount) ?? 0;
    final unit = _unitFromServerName(item.unit) ?? formatting.Unit.piece;
    return const formatting.QuantityFormatter().format(amount, unit, localizations);
  }

  static formatting.Unit? _unitFromServerName(String serverName) {
    for (final unit in formatting.Unit.values) {
      if (unit.name.toUpperCase() == serverName) {
        return unit;
      }
    }
    return null;
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
