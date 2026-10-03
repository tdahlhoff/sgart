import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../item_display_text.dart';
import '../../../../l10n/gen/app_localizations.dart';
import '../../../../shared/widgets/inline_action_error_text.dart';
import '../../../../shared/widgets/sgart_app_bar.dart';
import '../../../../shared/widgets/sgart_button.dart';
import '../../../../theme/sgart_theme_access.dart';
import '../../../../theme/tokens/sgart_shapes.dart';
import '../../../stores/data/store_chain_reference_cache.dart';
import '../../../stores/data/stores_api.dart';
import '../../../stores/presentation/store_picker_sheet.dart';
import '../../../trips/data/trips_api.dart';
import '../../../trips/presentation/trip_screen.dart';
import '../../data/item.dart';
import '../../data/item_suggestions_api.dart';
import '../../data/items_api.dart';
import '../../data/shopping_lists_api.dart';
import '../../print/print_share_sheet.dart';
import 'fast_add_field.dart';
import 'item_form_sheet.dart';
import 'list_detail_cubit.dart';
import 'list_detail_state.dart';
import 'move_target_sheet.dart';

/// The list detail screen (Story 2.3, AC6; Story 2.5, AC2/AC3/AC4/AC5): the tapped list's items in
/// creation order, each row showing name · quantity · optional note, with an empty state and
/// per-row edit/remove affordances. An Open list's only add surface is the persistent fast-add
/// field pinned at the top (AC4) — the Story 2.3 add button/sheet-add path is retired. "Einkauf
/// starten"/"Drucken / Teilen" sit in a fixed row at the bottom, below the scrollable item list, so
/// they stay reachable without scrolling. A Done list opens read-only — no fast-add field, no
/// suggestion panel, no action-button row, no edit/remove affordances render at all (AC5). Off-trip
/// there is no check/uncheck/postpone (Epic 3) and no store assignment (Story 2.6).
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
        .push(
          MaterialPageRoute<void>(
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
          ),
        )
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
          BlocBuilder<ListDetailCubit, ListDetailState>(
            builder: (context, state) {
              // Non-scrolling header row, not a fixed/overlaying footer — mirrors
              // `screen-list-detail.html`'s `.listctx` peer-action placement (DESIGN.md §4: tonal
              // actions are non-sticky). No actions render on a read-only (Done or In-Trip) list.
              if (state.status != ListDetailStatus.ready || state.isReadOnly) {
                return const SizedBox.shrink();
              }
              return _ActionButtonsBar(state: state, title: title);
            },
          ),
          Expanded(
            child: BlocBuilder<ListDetailCubit, ListDetailState>(
              builder: (context, state) {
                return switch (state.status) {
                  ListDetailStatus.loading => const Center(
                    child: CircularProgressIndicator(key: Key('item-list-loading')),
                  ),
                  ListDetailStatus.failure => const _FailureBody(),
                  ListDetailStatus.ready => _ReadyBody(state: state, title: title),
                };
              },
            ),
          ),
          BlocBuilder<ListDetailCubit, ListDetailState>(
            builder: (context, state) {
              // The fast-add field is the only add surface on an Open list (AC4); a Done list shows
              // neither the field nor its suggestion panel (AC5).
              if (state.status != ListDetailStatus.ready) {
                return const SizedBox.shrink();
              }
              final cubit = context.read<ListDetailCubit>();
              if (state.isReadOnly) {
                // No add field here, but a rejected action (e.g. a trip that cannot start) still
                // reports itself in the same place as everywhere else.
                return InlineActionErrorText(error: state.actionError);
              }
              return FastAddField(cubit: cubit);
            },
          ),
        ],
      ),
    );
  }
}

class _ReadyBody extends StatefulWidget {
  const _ReadyBody({required this.state, required this.title});

  final ListDetailState state;
  final String title;

  @override
  State<_ReadyBody> createState() => _ReadyBodyState();
}

class _ReadyBodyState extends State<_ReadyBody> {
  static const int _maxScrollCorrections = 3;
  static const int _extentSettleFrames = 12;

  final ScrollController _scrollController = ScrollController();

  @override
  void dispose() {
    _scrollController.dispose();
    super.dispose();
  }

  /// New items are appended, so showing the one just added means scrolling to the end — after the
  /// frame that lays it out. The scroll extent can still change once that frame has settled (the row's
  /// real height, the keyboard; a jump with animations off is clamped by it), so every scroll is
  /// followed by a short wait (a few frames, not a timer) and a check that it really reached the end, and goes on if not.
  void _scrollToTheEnd({int remainingCorrections = _maxScrollCorrections}) {
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      if (!mounted || !_scrollController.hasClients) {
        return;
      }
      final end = _scrollController.position.maxScrollExtent;
      if (MediaQuery.disableAnimationsOf(context)) {
        _scrollController.jumpTo(end);
      } else {
        await _scrollController.animateTo(end, duration: const Duration(milliseconds: 300), curve: Curves.easeOutCubic);
      }
      for (var frame = 0; frame < _extentSettleFrames; frame++) {
        await WidgetsBinding.instance.endOfFrame;
      }
      final isStillShort =
          mounted &&
          _scrollController.hasClients &&
          _scrollController.position.pixels < _scrollController.position.maxScrollExtent - 1;
      if (isStillShort && remainingCorrections > 0) {
        _scrollToTheEnd(remainingCorrections: remainingCorrections - 1);
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final state = widget.state;
    final localizations = AppLocalizations.of(context);
    final cubit = context.read<ListDetailCubit>();

    return BlocListener<ListDetailCubit, ListDetailState>(
      listenWhen: (previous, current) =>
          current.lastAddedItemId != null && current.lastAddedItemId != previous.lastAddedItemId,
      listener: (context, state) => _scrollToTheEnd(),
      child: SingleChildScrollView(
        controller: _scrollController,
        // Rows run edge to edge so their alternating background bands do too; each row insets its own
        // content by the card padding.
        padding: const EdgeInsets.symmetric(vertical: SgartShapes.cardPadding),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (state.items.isEmpty)
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: SgartShapes.cardPadding),
                child: Text(localizations.itemsEmptyState, key: const Key('item-list-empty-state')),
              )
            else
              for (final (index, item) in state.items.indexed)
                _ItemRow(
                  item: item,
                  index: index,
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
          ],
        ),
      ),
    );
  }
}

/// „Einkauf starten" and „Drucken / Teilen" side by side in a non-scrolling header row above the
/// item list (Story 3.1, AC1, AC6; Story 3.5) — reachable without scrolling, mirroring
/// `screen-list-detail.html`'s `.listctx` peer-action placement. Deliberately *not* a fixed/
/// overlaying footer: DESIGN.md §4 calls tonal/terminal actions non-sticky, and the sibling
/// Active-Trip screen spells out why ("List is the hero. No sticky bottom bar.") — a persistent
/// footer here would read as a commerce checkout bar, which SGART (a coordination tool, not
/// commerce) deliberately avoids. A flat hairline separates it from the scrollable content below,
/// matching the design system's flat-forward elevation (hairline, no shadow) rather than a
/// Material drop shadow. Rendered only on an Open list — hidden on In-Trip and Done alike, since
/// both key off the same `isReadOnly` flag (UX-DR7/UX-DR17).
class _ActionButtonsBar extends StatelessWidget {
  const _ActionButtonsBar({required this.state, required this.title});

  final ListDetailState state;
  final String title;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);
    final cubit = context.read<ListDetailCubit>();

    final colors = context.sgartColors;
    return DecoratedBox(
      key: const Key('list-detail-action-bar'),
      // The same white surface as the header and the add bar below the list: the three chrome bars
      // frame the tinted list, and the tonal buttons stand out more on white than on the faintly
      // blue page background.
      decoration: BoxDecoration(
        color: colors.surface,
        border: Border(bottom: BorderSide(color: colors.border, width: SgartShapes.hairline)),
      ),
      child: Padding(
        padding: const EdgeInsets.all(SgartShapes.cardPadding),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: SgartButton(
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
                          ScaffoldMessenger.of(
                            context,
                          ).showSnackBar(SnackBar(content: Text(localizations.tripStartedConfirmation)));
                          // Story 3.2, AC4, Cl. 3 — a started trip now navigates straight to the trip
                          // screen (3.1 deferred this; it used to end at the toast alone).
                          // Story 3.4: if the trip was completed, pop list-detail too (the list is now
                          // Done and can no longer be edited; the lists-view onEditableReturn callback
                          // handles the archive invalidation + overview refresh).
                          final completed = await TripScreen.push(
                            context,
                            householdId: cubit.householdId,
                            listId: cubit.listId,
                            listTitle: title,
                          );
                          if (completed == true && context.mounted) {
                            Navigator.of(context).pop();
                          }
                        }
                      },
              ),
            ),
            const SizedBox(width: SgartShapes.space2),
            Expanded(
              child: SgartButton(
                key: const Key('list-detail-print-share'),
                label: localizations.printShareAction,
                variant: SgartButtonVariant.tonal,
                onPressed: () => showPrintShareSheet(
                  context,
                  title: title,
                  items: state.items,
                  stores: state.stores,
                  storesApi: context.read<StoresApi>(),
                  referenceCache: context.read<StoreChainReferenceCache>(),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// Opacity of the neutral tint on every second item row — enough to separate rows, quiet enough
/// that the text stays the hero.
const double _stripeAlpha = 0.06;

class _ItemRow extends StatelessWidget {
  const _ItemRow({
    required this.item,
    required this.index,
    required this.isReadOnly,
    required this.storeName,
    required this.onEdit,
    required this.onRemove,
    required this.onMove,
    required this.onAssignStore,
  });

  final Item item;

  /// The row's position in the list; every second row gets a tinted background.
  final int index;
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
    final colors = context.sgartColors;
    final subtitle = formatItemSubtitle(item, localizations);
    final isDone = item.status == ItemStatus.done;
    final isDiscarded = item.status == ItemStatus.discarded;
    final isTerminal = isReadOnly && (isDone || isDiscarded);
    // Story 3.6, AC5 — reserved by an in-flight move transfer. Independent of isReadOnly: a
    // pending item can occur on an otherwise-editable Open list, so it needs its own
    // non-interactive treatment (mirrors the server's fail-fast lock) rather than piggy-backing on
    // the read-only-list terminal styling above.
    final isPending = item.transferPending;

    final tile = ListTile(
      key: Key('item-row-${item.itemId}'),
      contentPadding: const EdgeInsets.symmetric(horizontal: SgartShapes.cardPadding),
      title: Text(
        item.name,
        style: (isTerminal || isPending)
            ? TextStyle(decoration: TextDecoration.lineThrough, color: colors.textSecondary)
            : null,
      ),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          if (isPending)
            Text(
              localizations.itemTransferPendingLabel,
              key: Key('item-pending-label-${item.itemId}'),
              style: TextStyle(color: colors.textSecondary),
            )
          else if (isTerminal && isDiscarded)
            Text(localizations.itemDiscardedLabel, style: TextStyle(color: colors.textSecondary)),
          Text(subtitle, key: Key('item-quantity-${item.itemId}')),
          const SizedBox(height: SgartShapes.spaceHalfUnit),
          _StoreChip(
            key: Key('item-store-chip-${item.itemId}'),
            storeName: storeName,
            isReadOnly: isReadOnly || isPending,
            onTap: onAssignStore,
          ),
        ],
      ),
      isThreeLine: true,
      trailing: (isReadOnly || isPending)
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

    if (!isTerminal && !isPending) {
      if (index.isEven) {
        return tile;
      }
      return ColoredBox(
        key: Key('item-row-stripe-${item.itemId}'),
        color: colors.textSecondary.withValues(alpha: _stripeAlpha),
        child: tile,
      );
    }
    return ColoredBox(
      key: isPending ? Key('item-row-pending-${item.itemId}') : null,
      color: isPending
          ? colors.textSecondary.withValues(alpha: 0.08)
          : (isDone ? colors.success.withValues(alpha: 0.12) : colors.textSecondary.withValues(alpha: 0.08)),
      child: tile,
    );
  }
}

/// The item row's store chip (Story 2.6, AC1, AC4, AC5, UX-DR5): shows the resolved [storeName], or
/// the ghost „+ Geschäft" label when unresolved (unassigned, or assigned to an archived/absent
/// store — both render identically, AC4). Tappable only on an Open list ([isReadOnly] `false`) — a
/// Done list's chip is inert and opens no picker (AC5), mirroring the row's other affordances.
/// Tap area around the store chip, above and below it.
const double _storeChipTapMargin = 6;

class _StoreChip extends StatelessWidget {
  const _StoreChip({super.key, required this.storeName, required this.isReadOnly, required this.onTap});

  final String? storeName;
  final bool isReadOnly;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);
    final label = storeName ?? localizations.itemStoreUnassignedChip;
    final colors = context.sgartColors;
    final chip = DecoratedBox(
      decoration: BoxDecoration(
        color: colors.chipBackground,
        border: Border.all(color: colors.border),
        borderRadius: SgartShapes.pill,
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: SgartShapes.space4 - 2, vertical: SgartShapes.spaceUnit + 2),
        child: Text(label, style: Theme.of(context).textTheme.labelMedium?.copyWith(color: colors.onNeutralTint)),
      ),
    );
    if (isReadOnly) {
      return chip;
    }
    // The chip stays small and hugs the left edge of the row's text. The tap area reaches a few pixels
    // past it — enough to hit comfortably, little enough that rows stay compact (well above WCAG 2.5.8's
    // 24px minimum, below Material's 48dp ideal, which the row's icon buttons keep).
    return Align(
      alignment: Alignment.centerLeft,
      widthFactor: 1,
      child: Semantics(
        button: true,
        label: localizations.itemStoreAssignAction,
        child: GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: _storeChipTapMargin),
            child: InkWell(onTap: onTap, borderRadius: SgartShapes.pill, child: chip),
          ),
        ),
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
