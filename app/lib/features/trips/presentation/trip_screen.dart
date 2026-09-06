import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/formatting/quantity_formatter.dart' as formatting;
import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/errors/error_message_resolver.dart';
import '../../../shared/widgets/sgart_app_bar.dart';
import '../../../shared/widgets/sgart_button.dart';
import '../../../theme/sgart_theme_access.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import '../../lists/data/item.dart';
import '../../lists/data/items_api.dart';
import '../../lists/data/shopping_lists_api.dart';
import '../../stores/data/store_chain_reference_cache.dart';
import '../../stores/data/stores_api.dart';
import '../data/trips_api.dart';
import '../../stores/presentation/store_picker_sheet.dart';
import 'postpone_target_sheet.dart';
import 'trip_cubit.dart';
import 'trip_item_actions_sheet.dart';
import 'trip_state.dart';

/// The trip screen (Stories 3.2/3.3/3.4, AC1, AC2, AC3, AC5, Cl. 2/5/7/9/12;
/// `screen-active-trip.html`): one section per trip store (name · chain, item count, item rows
/// with checkboxes + ⋯ actions sheet) plus a „Noch nicht zugeordnet" section and the
/// „Einkauf abschließen" tonal action at the list end (Story 3.4, AC1). Reads its [TripCubit]
/// from the enclosing provider (scoped to the list by the caller).
class TripScreen extends StatelessWidget {
  const TripScreen({super.key, required this.listTitle});

  /// Already-derived display title (the list's name, or the „Liste N" fallback) — this screen
  /// never re-derives it.
  final String listTitle;

  /// Pushes this screen, re-providing the APIs + a household/list-scoped [TripCubit]. Returns
  /// `true` when the trip was completed, `false`/`null` when it was dismissed without completing.
  static Future<bool?> push(
    BuildContext context, {
    required String householdId,
    required String listId,
    required String listTitle,
  }) {
    final tripsApi = context.read<TripsApi>();
    final itemsApi = context.read<ItemsApi>();
    final storesApi = context.read<StoresApi>();
    final shoppingListsApi = context.read<ShoppingListsApi>();
    final storeChainReferenceCache = context.read<StoreChainReferenceCache>();
    return Navigator.of(context).push<bool>(MaterialPageRoute<bool>(
      builder: (_) => RepositoryProvider<TripsApi>.value(
        value: tripsApi,
        child: RepositoryProvider<ItemsApi>.value(
          value: itemsApi,
          child: RepositoryProvider<StoresApi>.value(
            value: storesApi,
            child: RepositoryProvider<ShoppingListsApi>.value(
              value: shoppingListsApi,
              child: RepositoryProvider<StoreChainReferenceCache>.value(
                value: storeChainReferenceCache,
                child: BlocProvider<TripCubit>(
                  create: (context) => TripCubit(
                    tripsApi: context.read<TripsApi>(),
                    itemsApi: context.read<ItemsApi>(),
                    storesApi: context.read<StoresApi>(),
                    householdId: householdId,
                    listId: listId,
                  )..bootstrap(),
                  child: TripScreen(listTitle: listTitle),
                ),
              ),
            ),
          ),
        ),
      ),
    ));
  }

  @override
  Widget build(BuildContext context) {
    return BlocListener<TripCubit, TripState>(
      listenWhen: (previous, current) => !previous.completed && current.completed,
      listener: (context, state) => Navigator.of(context).pop(true),
      child: Scaffold(
        key: const Key('trip-screen'),
        appBar: SgartAppBar(title: listTitle),
        body: BlocBuilder<TripCubit, TripState>(
          builder: (context, state) {
            return switch (state.status) {
              TripStatus.loading => const Center(child: CircularProgressIndicator(key: Key('trip-loading'))),
              TripStatus.failure => const _FailureBody(),
              TripStatus.ready => _ReadyBody(state: state),
            };
          },
        ),
      ),
    );
  }
}

class _ReadyBody extends StatelessWidget {
  const _ReadyBody({required this.state});

  final TripState state;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);
    final cubit = context.read<TripCubit>();

    return SingleChildScrollView(
      padding: const EdgeInsets.all(SgartShapes.cardPadding),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(localizations.tripScreenTitle, style: Theme.of(context).textTheme.labelLarge),
          const SizedBox(height: SgartShapes.space2),
          _ProgressHeader(doneCount: state.doneCount, totalCount: state.totalCount),
          const SizedBox(height: SgartShapes.space4),
          for (final group in state.groups)
            _StoreGroupSection(
              group: group,
              storeName: cubit.state.storeFor(group.storeId)?.name ?? group.storeId,
              onActions: (itemId) => _openItemActionsSheet(context, itemId),
            ),
          _UnassignedSection(
            items: state.unassignedItems,
            onActions: (itemId) => _openItemActionsSheet(context, itemId),
          ),
          if (state.actionError != null) ...[
            const SizedBox(height: SgartShapes.space4),
            Text(
              localizedMessageForErrorCode(localizations, state.actionError!.code),
              key: const Key('trip-action-error'),
            ),
          ],
          const SizedBox(height: SgartShapes.space4),
          SgartButton(
            key: const Key('trip-add-store'),
            label: localizations.tripAddStoreAction,
            variant: SgartButtonVariant.tonal,
            onPressed: state.isSubmitting ? null : () => _openAddStorePicker(context),
          ),
          const SizedBox(height: SgartShapes.space2),
          Semantics(
            button: true,
            label: localizations.tripCompleteAction,
            child: SgartButton(
              key: const Key('trip-complete-action'),
              label: localizations.tripCompleteAction,
              variant: SgartButtonVariant.tonal,
              onPressed: state.isSubmitting ? null : () => _openCompletionDialog(context),
            ),
          ),
        ],
      ),
    );
  }

  void _openItemActionsSheet(BuildContext context, String itemId) {
    final cubit = context.read<TripCubit>();
    showTripItemActionsSheet(
      context,
      cubit: cubit,
      itemId: itemId,
      shoppingListsApi: context.read<ShoppingListsApi>(),
      storesApi: context.read<StoresApi>(),
      referenceCache: context.read<StoreChainReferenceCache>(),
    );
  }

  Future<void> _openAddStorePicker(BuildContext context) async {
    final cubit = context.read<TripCubit>();
    final offeredStores = cubit.state.stores.where((store) => !cubit.state.storeIds.contains(store.storeId)).toList();
    // No onInlineStoreCreated hook here — the post-picker `addStoreToTrip(selected)` already
    // handles both the existing-store and the inline-created-store cases. Hooking both would
    // call AddStoreToTrip twice for an inline-created store.
    final selected = await showStorePickerSheet(
      context,
      stores: offeredStores,
      storesApi: context.read<StoresApi>(),
      referenceCache: context.read<StoreChainReferenceCache>(),
      householdId: cubit.householdId,
    );
    if (selected != null) {
      cubit.addStoreToTrip(selected);
    }
  }

  Future<void> _openCompletionDialog(BuildContext context) async {
    final cubit = context.read<TripCubit>();
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (sheetContext) => _CompletionSheetBody(
        cubit: cubit,
        shoppingListsApi: context.read<ShoppingListsApi>(),
        parentContext: context,
      ),
    );
  }
}

class _ProgressHeader extends StatelessWidget {
  const _ProgressHeader({required this.doneCount, required this.totalCount});

  final int doneCount;
  final int totalCount;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);
    final progress = totalCount == 0 ? 0.0 : doneCount / totalCount;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(localizations.tripProgressLabel(doneCount, totalCount)),
        const SizedBox(height: SgartShapes.spaceHalfUnit),
        LinearProgressIndicator(
          key: const Key('trip-progress-bar'),
          value: progress,
        ),
      ],
    );
  }
}

class _StoreGroupSection extends StatelessWidget {
  const _StoreGroupSection({
    required this.group,
    required this.storeName,
    required this.onActions,
  });

  final TripStoreGroup group;
  final String storeName;
  final ValueChanged<String> onActions;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return Padding(
      key: Key('trip-store-group-${group.storeId}'),
      padding: const EdgeInsets.only(bottom: SgartShapes.space4),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(storeName, style: Theme.of(context).textTheme.titleMedium),
              Text(
                localizations.tripStoreItemCount(group.items.length),
                style: Theme.of(context).textTheme.bodySmall,
              ),
            ],
          ),
          for (final item in group.items)
            _TripItemRow(
              item: item,
              onActions: () => onActions(item.itemId),
            ),
        ],
      ),
    );
  }
}

class _UnassignedSection extends StatelessWidget {
  const _UnassignedSection({required this.items, required this.onActions});

  final List<Item> items;
  final ValueChanged<String> onActions;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return Padding(
      key: const Key('trip-unassigned-group'),
      padding: const EdgeInsets.only(bottom: SgartShapes.space4),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(localizations.tripUnassignedGroupLabel, style: Theme.of(context).textTheme.titleMedium),
          if (items.isEmpty)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: SgartShapes.space2),
              child: Text(localizations.tripUnassignedEmptyState, key: const Key('trip-unassigned-empty-state')),
            )
          else
            for (final item in items)
              _TripItemRow(
                item: item,
                onActions: () => onActions(item.itemId),
              ),
        ],
      ),
    );
  }
}

class _TripItemRow extends StatelessWidget {
  const _TripItemRow({required this.item, required this.onActions});

  final Item item;
  final VoidCallback onActions;

  bool get _isDone => item.status == ItemStatus.done;
  bool get _isDiscarded => item.status == ItemStatus.discarded;

  /// Story 3.6, AC5 — reserved by an in-flight move/postpone transfer. Takes precedence over the
  /// status-based rendering below: a pending item is always non-interactive (mirroring the server's
  /// fail-fast lock) and shows „wird verschoben…" until the next fetch/refresh reconciles it.
  bool get _isPending => item.transferPending;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);
    final quantityText = _formatQuantity(item, localizations);
    final subtitle = item.note == null ? quantityText : '$quantityText · ${item.note}';
    final cubit = context.read<TripCubit>();
    final colors = context.sgartColors;

    final checkboxSemantic = _isDone ? localizations.tripItemUncheckSemantic : localizations.tripItemCheckOffSemantic;

    return ColoredBox(
      key: _isPending
          ? Key('trip-item-pending-${item.itemId}')
          : (_isDone ? Key('trip-item-done-${item.itemId}') : (_isDiscarded ? Key('trip-item-discarded-${item.itemId}') : null)),
      color: _isPending
          ? colors.textSecondary.withValues(alpha: 0.08)
          : (_isDone
              ? colors.success.withValues(alpha: 0.12)
              : (_isDiscarded ? colors.textSecondary.withValues(alpha: 0.08) : Colors.transparent)),
      child: ListTile(
        key: Key('trip-item-${item.itemId}'),
        contentPadding: EdgeInsets.zero,
        leading: Semantics(
          label: checkboxSemantic,
          child: Checkbox(
            key: Key('trip-item-checkbox-${item.itemId}'),
            value: _isDone,
            onChanged: _isPending
                ? null
                : (_) {
                    if (_isDone) {
                      cubit.uncheck(item.itemId);
                    } else {
                      cubit.checkOff(item.itemId);
                    }
                  },
          ),
        ),
        title: Text(
          item.name,
          style: (_isDone || _isDiscarded || _isPending)
              ? TextStyle(decoration: TextDecoration.lineThrough, color: colors.textSecondary)
              : null,
        ),
        subtitle: _isPending
            ? Text(
                localizations.itemTransferPendingLabel,
                key: Key('trip-item-pending-label-${item.itemId}'),
                style: TextStyle(color: colors.textSecondary),
              )
            : (_isDiscarded
                ? Text(localizations.itemDiscardedLabel, style: TextStyle(color: colors.textSecondary))
                : Text(subtitle)),
        // Status-dependent trailing (Story 3.4, Cl. 12; Story 3.6, AC5):
        //   pending   → no trailing, non-interactive (mirrors the server's fail-fast lock)
        //   OPEN      → ⋯ actions sheet (reroute / transfer / discard)
        //   DONE      → no trailing (uncheck via checkbox only)
        //   DISCARDED → UNDO button (→ OPEN) only, no ⋯
        trailing: _buildTrailing(localizations, cubit),
      ),
    );
  }

  Widget? _buildTrailing(AppLocalizations localizations, TripCubit cubit) {
    if (_isPending) {
      return null;
    }
    if (_isDone) {
      return null;
    }
    if (_isDiscarded) {
      return Semantics(
        button: true,
        label: localizations.tripItemUndoDiscardAction,
        child: SizedBox(
          width: 48,
          height: 48,
          child: IconButton(
            key: Key('trip-item-undo-discard-${item.itemId}'),
            icon: const Icon(Icons.undo),
            tooltip: localizations.tripItemUndoDiscardAction,
            onPressed: () => cubit.uncheck(item.itemId),
          ),
        ),
      );
    }
    return Semantics(
      button: true,
      label: localizations.tripItemActionsSheetTitle,
      child: SizedBox(
        width: 48,
        height: 48,
        child: IconButton(
          key: Key('trip-item-actions-${item.itemId}'),
          icon: const Icon(Icons.more_horiz),
          tooltip: localizations.tripItemActionsSheetTitle,
          onPressed: onActions,
        ),
      ),
    );
  }

  String _formatQuantity(Item item, AppLocalizations localizations) {
    final amount = double.tryParse(item.amount) ?? 0;
    final unit = formatting.unitFromServerName(item.unit) ?? formatting.Unit.piece;
    return const formatting.QuantityFormatter().format(amount, unit, localizations);
  }
}

/// The guided completion sheet (Story 3.4, AC1/AC3/AC4/AC5/AC6): "Fertig?" summary → per-open-item
/// Übernehmen/Verwerfen choices → "Einkauf abschließen" confirm / "Doch noch weiter einkaufen"
/// cancel. E4: no open items → skips straight to confirm.
class _CompletionSheetBody extends StatelessWidget {
  const _CompletionSheetBody({
    required this.cubit,
    required this.shoppingListsApi,
    required this.parentContext,
  });

  final TripCubit cubit;
  final ShoppingListsApi shoppingListsApi;
  final BuildContext parentContext;

  @override
  Widget build(BuildContext context) {
    return BlocBuilder<TripCubit, TripState>(
      bloc: cubit,
      builder: (context, state) {
        final localizations = AppLocalizations.of(context);
        final doneCount = state.doneCount;
        final totalCount = state.totalCount;
        final openItems = state.openItems;

        return Padding(
          key: const Key('trip-completion-sheet'),
          padding: EdgeInsets.only(
            left: SgartShapes.cardPadding,
            right: SgartShapes.cardPadding,
            top: SgartShapes.cardPadding,
            bottom: MediaQuery.of(context).viewInsets.bottom + SgartShapes.cardPadding,
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text(localizations.tripCompleteDialogTitle, style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: SgartShapes.space2),
              Text(localizations.tripProgressLabel(doneCount, totalCount)),
              if (openItems.isNotEmpty) ...[
                const SizedBox(height: SgartShapes.space4),
                Text(localizations.tripCompleteLeftoverPrompt),
                const SizedBox(height: SgartShapes.space2),
                for (final item in openItems)
                  _LeftoverItemRow(
                    item: item,
                    cubit: cubit,
                    shoppingListsApi: shoppingListsApi,
                    parentContext: parentContext,
                    sheetContext: context,
                  ),
              ],
              const SizedBox(height: SgartShapes.space4),
              Semantics(
                button: true,
                label: localizations.tripCompleteAction,
                child: SgartButton(
                  key: const Key('trip-completion-confirm'),
                  label: localizations.tripCompleteAction,
                  onPressed: state.isSubmitting
                      ? null
                      : () {
                          Navigator.of(context).pop();
                          cubit.completeTrip();
                        },
                ),
              ),
              const SizedBox(height: SgartShapes.space2),
              SgartButton(
                key: const Key('trip-completion-cancel'),
                label: localizations.tripKeepShoppingAction,
                variant: SgartButtonVariant.tonal,
                onPressed: () => Navigator.of(context).pop(),
              ),
            ],
          ),
        );
      },
    );
  }
}

class _LeftoverItemRow extends StatelessWidget {
  const _LeftoverItemRow({
    required this.item,
    required this.cubit,
    required this.shoppingListsApi,
    required this.parentContext,
    required this.sheetContext,
  });

  final Item item;
  final TripCubit cubit;
  final ShoppingListsApi shoppingListsApi;
  final BuildContext parentContext;
  final BuildContext sheetContext;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return Padding(
      padding: const EdgeInsets.symmetric(vertical: SgartShapes.spaceHalfUnit),
      child: Row(
        children: [
          Expanded(child: Text(item.name)),
          Semantics(
            button: true,
            label: localizations.tripLeftoverTransferAction,
            child: SizedBox(
              height: 48,
              child: TextButton(
                key: Key('trip-leftover-transfer-${item.itemId}'),
                onPressed: () {
                  Navigator.of(sheetContext).pop();
                  showPostponeTargetSheet(
                    parentContext,
                    cubit: cubit,
                    shoppingListsApi: shoppingListsApi,
                    itemId: item.itemId,
                    householdId: cubit.householdId,
                    sourceListId: cubit.listId,
                  );
                },
                child: Text(localizations.tripLeftoverTransferAction),
              ),
            ),
          ),
          const SizedBox(width: SgartShapes.spaceHalfUnit),
          Semantics(
            button: true,
            label: localizations.tripItemDiscardAction,
            child: SizedBox(
              height: 48,
              child: TextButton(
                key: Key('trip-leftover-discard-${item.itemId}'),
                onPressed: () => cubit.discard(item.itemId),
                child: Text(localizations.tripItemDiscardAction),
              ),
            ),
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
            Text(localizations.errorGenericFallback, key: const Key('trip-load-error')),
            const SizedBox(height: SgartShapes.space4),
            SgartButton(
              key: const Key('trip-retry-button'),
              label: localizations.householdsRetryButtonLabel,
              onPressed: () => context.read<TripCubit>().refresh(),
            ),
          ],
        ),
      ),
    );
  }
}
