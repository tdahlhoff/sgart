import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/formatting/quantity_formatter.dart' as formatting;
import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/errors/error_message_resolver.dart';
import '../../../shared/widgets/sgart_app_bar.dart';
import '../../../shared/widgets/sgart_button.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import '../../lists/data/item.dart';
import '../../lists/data/items_api.dart';
import '../../stores/data/store_chain_reference_cache.dart';
import '../../stores/data/store_summary.dart';
import '../../stores/data/stores_api.dart';
import '../../stores/presentation/store_picker_sheet.dart';
import '../data/trips_api.dart';
import 'trip_cubit.dart';
import 'trip_state.dart';

/// The trip screen (Story 3.2, AC1, AC2, AC3, AC5, Cl. 2/5/7/9; UX-DR7, `screen-active-trip.html`):
/// SGART's first trip screen — one section per trip store (name · chain, item count, item rows)
/// plus a „Noch nicht zugeordnet" section for unassigned/non-trip/archived-store items. Items are
/// **status-read-only** here — no checkbox, no progress bar, no „Einkauf abschließen" (Cl. 2, those
/// are Stories 3.3/3.4). Tapping a store chip/„Zuordnen" opens the reroute picker scoped to the
/// trip's stores; „Geschäft hinzufügen" opens the same picker to add a store to the trip. Reads its
/// [TripCubit] from the enclosing provider (scoped to the list by the caller).
class TripScreen extends StatelessWidget {
  const TripScreen({super.key, required this.listTitle});

  /// Already-derived display title (the list's name, or the „Liste N" fallback) — this screen never
  /// re-derives it.
  final String listTitle;

  /// Pushes this screen, re-providing [TripsApi] + [ItemsApi] + [StoresApi] +
  /// [StoreChainReferenceCache] (needed by the reroute/add-store picker) + a household/list-scoped
  /// [TripCubit] (mirrors `ListDetailPage.push`'s re-providing pattern, Story 3.2, AC4).
  static Future<void> push(
    BuildContext context, {
    required String householdId,
    required String listId,
    required String listTitle,
  }) {
    final tripsApi = context.read<TripsApi>();
    final itemsApi = context.read<ItemsApi>();
    final storesApi = context.read<StoresApi>();
    final storeChainReferenceCache = context.read<StoreChainReferenceCache>();
    return Navigator.of(context).push(MaterialPageRoute<void>(
      builder: (_) => RepositoryProvider<TripsApi>.value(
        value: tripsApi,
        child: RepositoryProvider<ItemsApi>.value(
          value: itemsApi,
          child: RepositoryProvider<StoresApi>.value(
            value: storesApi,
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
    ));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
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
          for (final group in state.groups)
            _StoreGroupSection(
              group: group,
              storeName: cubit.state.storeFor(group.storeId)?.name ?? group.storeId,
              onReroute: (itemId) => _openReroutePicker(context, itemId),
            ),
          _UnassignedSection(items: state.unassignedItems, onAssign: (itemId) => _openReroutePicker(context, itemId)),
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
        ],
      ),
    );
  }

  Future<void> _openReroutePicker(BuildContext context, String itemId) async {
    final cubit = context.read<TripCubit>();
    final tripStores = cubit.state.storeIds.map(cubit.state.storeFor).whereType<StoreSummary>().toList();
    final selected = await showStorePickerSheet(
      context,
      stores: tripStores,
      storesApi: context.read<StoresApi>(),
      referenceCache: context.read<StoreChainReferenceCache>(),
      householdId: _householdIdOf(context),
      onInlineStoreCreated: (created) => cubit.addStoreToTrip(created),
    );
    // Only reroute once the target is actually in the trip: an inline-created store whose
    // add-to-trip was rejected reverts out of `storeIds`, so rerouting to it would strand the item
    // in „Noch nicht zugeordnet" and wipe the add-store error. Its error stays visible instead.
    if (selected != null && cubit.state.storeIds.contains(selected.storeId)) {
      cubit.reroute(itemId, selected.storeId);
    }
  }

  Future<void> _openAddStorePicker(BuildContext context) async {
    final cubit = context.read<TripCubit>();
    // Offer the household's active stores not already in the trip (an already-in-trip pick would be
    // a convergent no-op server-side, Cl. 9's optimistic path is simpler when we just filter here).
    final offeredStores = cubit.state.stores.where((store) => !cubit.state.storeIds.contains(store.storeId)).toList();
    // No `onInlineStoreCreated` hook here: the sheet pops the chosen store (existing or inline-created)
    // and the single `addStoreToTrip` below adds it exactly once. Passing the hook too would add an
    // inline-created store twice (a redundant convergent-no-op POST + a spurious isSubmitting flash).
    final selected = await showStorePickerSheet(
      context,
      stores: offeredStores,
      storesApi: context.read<StoresApi>(),
      referenceCache: context.read<StoreChainReferenceCache>(),
      householdId: _householdIdOf(context),
    );
    if (selected != null) {
      cubit.addStoreToTrip(selected);
    }
  }

  String _householdIdOf(BuildContext context) => context.read<TripCubit>().householdId;
}

class _StoreGroupSection extends StatelessWidget {
  const _StoreGroupSection({required this.group, required this.storeName, required this.onReroute});

  final TripStoreGroup group;
  final String storeName;
  final ValueChanged<String> onReroute;

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
              actionLabel: localizations.tripItemRerouteAction,
              onTap: () => onReroute(item.itemId),
            ),
        ],
      ),
    );
  }
}

class _UnassignedSection extends StatelessWidget {
  const _UnassignedSection({required this.items, required this.onAssign});

  final List<Item> items;
  final ValueChanged<String> onAssign;

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
                actionLabel: localizations.tripItemAssignAction,
                onTap: () => onAssign(item.itemId),
              ),
        ],
      ),
    );
  }
}

class _TripItemRow extends StatelessWidget {
  const _TripItemRow({required this.item, required this.actionLabel, required this.onTap});

  final Item item;
  final String actionLabel;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);
    final quantityText = _formatQuantity(item, localizations);
    final subtitle = item.note == null ? quantityText : '$quantityText · ${item.note}';

    return ListTile(
      key: Key('trip-item-${item.itemId}'),
      contentPadding: EdgeInsets.zero,
      title: Text(item.name),
      subtitle: Text(subtitle),
      trailing: Semantics(
        button: true,
        label: actionLabel,
        child: TextButton(
          key: Key('trip-item-reroute-${item.itemId}'),
          onPressed: onTap,
          child: Text(actionLabel),
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
