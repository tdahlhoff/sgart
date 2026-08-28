import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../../l10n/formatting/quantity_formatter.dart' as formatting;
import '../../../../l10n/gen/app_localizations.dart';
import '../../../../shared/errors/error_message_resolver.dart';
import '../../../../shared/widgets/sgart_app_bar.dart';
import '../../../../shared/widgets/sgart_button.dart';
import '../../../../theme/tokens/sgart_shapes.dart';
import '../../../stores/data/store_chain_reference_cache.dart';
import '../../../stores/data/stores_api.dart';
import '../../../stores/presentation/store_picker_sheet.dart';
import '../../../trips/data/trips_api.dart';
import '../../data/item.dart';
import '../../data/item_suggestions_api.dart';
import '../../data/items_api.dart';
import '../../data/shopping_lists_api.dart';
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

  /// Pushes this screen, re-providing [ItemsApi] + [ItemSuggestionsApi] (Story 2.5, AC1) +
  /// [ShoppingListsApi] (needed by the move target picker, Story 2.4, AC7) + [StoresApi] +
  /// [StoreChainReferenceCache] (needed by the store picker, Story 2.6, AC1/AC2) + [TripsApi]
  /// (needed by the „Einkauf starten" action, Story 3.1, AC1) + a household/list-scoped
  /// [ListDetailCubit] (mirrors `HouseholdShell._openSwitcher`'s re-providing pattern). Calls
  /// [onEditableReturn] after the pushed route is popped, but only when the list was **opened**
  /// editable — a read-only Done list is immutable, so nothing can have changed on return and the
  /// callback is never worth firing. An Open list that transitions to In-Trip mid-session still
  /// fires the callback on return (it *was* opened editable, and a trip start is exactly the kind of
  /// change the overview needs to refresh for — the In-Trip label, Story 3.1 AC5). This guarantee
  /// lives here so no caller can accidentally pair a read-only push with an on-return refresh (which
  /// would, for the overview, snap the user off the Done archive by resetting its filter).
  static Future<void> push(
    BuildContext context, {
    required String householdId,
    required String listId,
    required String title,
    required bool isReadOnly,
    VoidCallback? onEditableReturn,
  }) {
    final itemsApi = context.read<ItemsApi>();
    final itemSuggestionsApi = context.read<ItemSuggestionsApi>();
    final shoppingListsApi = context.read<ShoppingListsApi>();
    final storesApi = context.read<StoresApi>();
    final storeChainReferenceCache = context.read<StoreChainReferenceCache>();
    final tripsApi = context.read<TripsApi>();
    return Navigator.of(context)
        .push(MaterialPageRoute<void>(
          builder: (_) => RepositoryProvider<ItemsApi>.value(
            value: itemsApi,
            child: RepositoryProvider<ItemSuggestionsApi>.value(
              value: itemSuggestionsApi,
              child: RepositoryProvider<ShoppingListsApi>.value(
                value: shoppingListsApi,
                child: RepositoryProvider<StoresApi>.value(
                  value: storesApi,
                  child: RepositoryProvider<StoreChainReferenceCache>.value(
                    value: storeChainReferenceCache,
                    child: RepositoryProvider<TripsApi>.value(
                      value: tripsApi,
                      child: BlocProvider<ListDetailCubit>(
                        create: (context) => ListDetailCubit(
                          itemsApi: context.read<ItemsApi>(),
                          itemSuggestionsApi: context.read<ItemSuggestionsApi>(),
                          storesApi: context.read<StoresApi>(),
                          tripsApi: context.read<TripsApi>(),
                          householdId: householdId,
                          listId: listId,
                          isReadOnly: isReadOnly,
                        )..bootstrap(),
                        child: ListDetailPage(title: title),
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ))
        .then((_) {
          if (!isReadOnly) {
            onEditableReturn?.call();
          }
        });
  }

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
                storeName: cubit.storeFor(item.storeId)?.name,
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
                onAssignStore: () async {
                  final selected = await showStorePickerSheet(
                    context,
                    stores: state.stores,
                    storesApi: context.read<StoresApi>(),
                    referenceCache: context.read<StoreChainReferenceCache>(),
                    householdId: cubit.householdId,
                  );
                  if (selected != null) {
                    // Pass the returned store so an inline-created one is registered in state
                    // (its chip resolves + a re-opened picker offers it) — Story 2.6 review patch.
                    cubit.assignStore(item.itemId, selected.storeId, store: selected);
                  }
                },
              ),
          if (state.actionError != null) ...[
            const SizedBox(height: SgartShapes.space4),
            Text(
              localizedMessageForErrorCode(localizations, state.actionError!.code),
              key: const Key('item-list-action-error'),
            ),
          ],
          // „Einkauf starten" is offered only on an Open list — hidden on In-Trip and Done alike,
          // since both key off the same isReadOnly flag (Story 3.1, AC1, AC6, UX-DR7/UX-DR17).
          if (!state.isReadOnly) ...[
            const SizedBox(height: SgartShapes.space4),
            SgartButton(
              key: const Key('list-detail-start-trip'),
              label: localizations.tripStartAction,
              variant: SgartButtonVariant.tonal,
              onPressed: state.isSubmitting
                  ? null
                  : () async {
                      final selection = await showTripStoreSelectionSheet(
                        context,
                        stores: state.stores,
                        storesApi: context.read<StoresApi>(),
                        referenceCache: context.read<StoreChainReferenceCache>(),
                        householdId: cubit.householdId,
                      );
                      if (selection == null || selection.isEmpty) {
                        return;
                      }
                      final started = await cubit.startTrip(selection.map((store) => store.storeId).toList());
                      if (started && context.mounted) {
                        ScaffoldMessenger.of(context)
                            .showSnackBar(SnackBar(content: Text(localizations.tripStartedConfirmation)));
                      }
                    },
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
    required this.storeName,
    required this.onEdit,
    required this.onRemove,
    required this.onMove,
    required this.onAssignStore,
  });

  final Item item;
  final bool isReadOnly;

  /// The resolved active store's name, or `null` for unassigned/archived (Story 2.6, AC4) — the row
  /// renders the „+ Geschäft" ghost chip in that case.
  final String? storeName;
  final VoidCallback onEdit;
  final VoidCallback onRemove;
  final VoidCallback onMove;
  final VoidCallback onAssignStore;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);
    final quantityText = _formatQuantity(item, localizations);
    final subtitle = item.note == null ? quantityText : '$quantityText · ${item.note}';

    return ListTile(
      key: Key('item-row-${item.itemId}'),
      contentPadding: EdgeInsets.zero,
      title: Text(item.name),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(subtitle, key: Key('item-quantity-${item.itemId}')),
          const SizedBox(height: SgartShapes.spaceHalfUnit),
          _StoreChip(
            key: Key('item-store-chip-${item.itemId}'),
            storeName: storeName,
            isReadOnly: isReadOnly,
            onTap: onAssignStore,
          ),
        ],
      ),
      isThreeLine: true,
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

/// The item row's store chip (Story 2.6, AC1, AC4, AC5, UX-DR5): shows the resolved [storeName], or
/// the ghost „+ Geschäft" label when unresolved (unassigned, or assigned to an archived/absent
/// store — both render identically, AC4). Tappable only on an Open list ([isReadOnly] `false`) — a
/// Done list's chip is inert and opens no picker (AC5), mirroring the row's other affordances.
class _StoreChip extends StatelessWidget {
  const _StoreChip({super.key, required this.storeName, required this.isReadOnly, required this.onTap});

  final String? storeName;
  final bool isReadOnly;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);
    final label = storeName ?? localizations.itemStoreUnassignedChip;
    final chip = DecoratedBox(
      decoration: BoxDecoration(
        border: Border.all(color: Theme.of(context).colorScheme.outline),
        borderRadius: SgartShapes.pill,
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: SgartShapes.space3, vertical: SgartShapes.spaceHalfUnit),
        child: Text(label, style: Theme.of(context).textTheme.labelMedium),
      ),
    );
    if (isReadOnly) {
      return chip;
    }
    return Semantics(
      button: true,
      label: localizations.itemStoreAssignAction,
      child: InkWell(
        onTap: onTap,
        borderRadius: SgartShapes.pill,
        child: ConstrainedBox(constraints: const BoxConstraints(minHeight: 48), child: Center(child: chip)),
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
