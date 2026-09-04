import 'package:flutter/material.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/widgets/sgart_button.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import '../../lists/data/shopping_lists_api.dart';
import '../../stores/data/store_chain_reference_cache.dart';
import '../../stores/data/store_summary.dart';
import '../../stores/data/stores_api.dart';
import '../../stores/presentation/store_picker_sheet.dart';
import 'postpone_target_sheet.dart';
import 'trip_cubit.dart';

/// Opens the per-row „Was tun mit diesem Artikel?" sheet (Story 3.4, Cl. 12): consolidates the
/// trip row's actions — **Anderes Geschäft** (reroute to a trip store), **auf andere Liste** /
/// **＋ Neue Liste** (transfer via postpone-to-list), and **Verwerfen** (discard). Replaces the
/// scattered `_openReroutePicker` / `_openPostponeSheet` trailing actions from Stories 3.2/3.3.
void showTripItemActionsSheet(
  BuildContext context, {
  required TripCubit cubit,
  required String itemId,
  required ShoppingListsApi shoppingListsApi,
  required StoresApi storesApi,
  required StoreChainReferenceCache referenceCache,
}) {
  showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    builder: (sheetContext) => _TripItemActionsSheetBody(
      cubit: cubit,
      itemId: itemId,
      shoppingListsApi: shoppingListsApi,
      storesApi: storesApi,
      referenceCache: referenceCache,
      parentContext: context,
    ),
  );
}

class _TripItemActionsSheetBody extends StatelessWidget {
  const _TripItemActionsSheetBody({
    required this.cubit,
    required this.itemId,
    required this.shoppingListsApi,
    required this.storesApi,
    required this.referenceCache,
    required this.parentContext,
  });

  final TripCubit cubit;
  final String itemId;
  final ShoppingListsApi shoppingListsApi;
  final StoresApi storesApi;
  final StoreChainReferenceCache referenceCache;
  // The parent context of the trip screen — needed to push subsequent sheets.
  final BuildContext parentContext;

  Future<void> _openReroute(BuildContext sheetContext) async {
    Navigator.of(sheetContext).pop();
    final tripStores = cubit.state.storeIds.map(cubit.state.storeFor).whereType<StoreSummary>().toList();
    final selected = await showStorePickerSheet(
      parentContext,
      stores: tripStores,
      storesApi: storesApi,
      referenceCache: referenceCache,
      householdId: cubit.householdId,
      onInlineStoreCreated: (created) => cubit.addStoreToTrip(created),
    );
    if (selected != null && cubit.state.storeIds.contains(selected.storeId)) {
      cubit.reroute(itemId, selected.storeId);
    }
  }

  void _openTransfer(BuildContext sheetContext) {
    Navigator.of(sheetContext).pop();
    showPostponeTargetSheet(
      parentContext,
      cubit: cubit,
      shoppingListsApi: shoppingListsApi,
      itemId: itemId,
      householdId: cubit.householdId,
      sourceListId: cubit.listId,
    );
  }

  void _discard(BuildContext sheetContext) {
    Navigator.of(sheetContext).pop();
    cubit.discard(itemId);
  }

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return Padding(
      key: Key('trip-item-actions-sheet-$itemId'),
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
          Text(localizations.tripItemActionsSheetTitle, style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: SgartShapes.space4),
          ListTile(
            key: Key('trip-item-reroute-action-$itemId'),
            contentPadding: EdgeInsets.zero,
            title: Text(localizations.tripItemRerouteInSheet),
            onTap: () => _openReroute(context),
          ),
          ListTile(
            key: Key('trip-item-transfer-action-$itemId'),
            contentPadding: EdgeInsets.zero,
            title: Text(localizations.tripItemTransferAction),
            onTap: () => _openTransfer(context),
          ),
          const SizedBox(height: SgartShapes.space4),
          SgartButton(
            key: Key('trip-item-discard-action-$itemId'),
            label: localizations.tripItemDiscardAction,
            variant: SgartButtonVariant.tonal,
            onPressed: () => _discard(context),
          ),
        ],
      ),
    );
  }
}
