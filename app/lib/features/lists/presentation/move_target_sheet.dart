import 'package:flutter/material.dart';
import 'package:uuid/uuid.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/widgets/sgart_button.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import '../data/item.dart';
import '../data/shopping_list_summary.dart';
import '../data/shopping_lists_api.dart';
import 'list_detail_cubit.dart';
import 'move_merge_dialog.dart';

/// Opens the move target picker (Story 2.4, AC3, AC4, AC7): lists the household's other Open lists
/// (the source excluded) plus a „＋ Neue Liste" entry. Picking an existing target runs the
/// collision check (Clarification 3) and either moves the item cleanly or opens the quantity-merge
/// sheet; picking „Neue Liste" prompts for a name, creates the list (client-minted id, AC3's
/// two-step), then always moves cleanly — a brand-new list never collides. The cubit and API are
/// captured by the caller (never re-read from the sheet's own context), mirroring `showItemFormSheet`.
void showMoveTargetSheet(
  BuildContext context, {
  required ListDetailCubit cubit,
  required ShoppingListsApi shoppingListsApi,
  required Item item,
  required String householdId,
  required String sourceListId,
}) {
  showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    builder: (sheetContext) => _MoveTargetSheetBody(
      cubit: cubit,
      shoppingListsApi: shoppingListsApi,
      item: item,
      householdId: householdId,
      sourceListId: sourceListId,
    ),
  );
}

/// The „Neue Liste" name prompt — a self-contained sheet (owns and disposes its own controller, so
/// the parent sheet never touches it after `pop`, avoiding a use-after-dispose during the pop
/// transition). Pops the entered name (possibly blank — an unnamed list is valid, AC1/AC2 mirror),
/// or `null` on dismiss. Mirrors `_CreateListSheetBody`'s structure minus the cubit dependency (this
/// prompt only collects a name; the caller does the actual create-then-move, AC3).
class _NewListNameSheetBody extends StatefulWidget {
  const _NewListNameSheetBody();

  @override
  State<_NewListNameSheetBody> createState() => _NewListNameSheetBodyState();
}

class _NewListNameSheetBodyState extends State<_NewListNameSheetBody> {
  final TextEditingController _nameController = TextEditingController();

  @override
  void dispose() {
    _nameController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return Padding(
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
          Text(localizations.listsCreateHeading, style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: SgartShapes.space4),
          TextField(
            key: const Key('move-target-new-list-name-field'),
            controller: _nameController,
            autofocus: true,
            maxLength: 120,
            decoration: InputDecoration(hintText: localizations.listsCreateNameHint),
          ),
          const SizedBox(height: SgartShapes.space4),
          SgartButton(
            key: const Key('move-target-new-list-submit-button'),
            label: localizations.listsCreateSubmitButtonLabel,
            onPressed: () => Navigator.of(context).pop(_nameController.text),
          ),
        ],
      ),
    );
  }
}

enum _LoadStatus { loading, ready, failure }

class _MoveTargetSheetBody extends StatefulWidget {
  const _MoveTargetSheetBody({
    required this.cubit,
    required this.shoppingListsApi,
    required this.item,
    required this.householdId,
    required this.sourceListId,
  });

  final ListDetailCubit cubit;
  final ShoppingListsApi shoppingListsApi;
  final Item item;
  final String householdId;
  final String sourceListId;

  @override
  State<_MoveTargetSheetBody> createState() => _MoveTargetSheetBodyState();
}

class _MoveTargetSheetBodyState extends State<_MoveTargetSheetBody> {
  _LoadStatus _status = _LoadStatus.loading;

  /// Guards `_selectTarget` against re-entry across its awaited collision read — a double-tap would
  /// otherwise `pop` twice, tearing down the list-detail page underneath the sheet.
  bool _selecting = false;

  /// The other Open lists, each paired with its 1-based creation-order position among *all* Open
  /// lists (computed before the source is excluded) — so a target's "Liste N" fallback matches the
  /// ordinal the Listen overview already shows for it (AC2), not a position renumbered by exclusion.
  List<(int orderIndex, ShoppingListSummary list)> _targets = const [];

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final openLists = await widget.shoppingListsApi.listOpenLists(widget.householdId);
      if (!mounted) {
        return;
      }
      setState(() {
        _targets = [
          for (final (index, list) in openLists.indexed)
            if (list.listId != widget.sourceListId) (index + 1, list),
        ];
        _status = _LoadStatus.ready;
      });
    } on Object {
      if (!mounted) {
        return;
      }
      setState(() => _status = _LoadStatus.failure);
    }
  }

  Future<void> _selectTarget(String targetListId, String targetListName) async {
    if (_selecting) {
      return;
    }
    _selecting = true;
    final navigator = Navigator.of(context);
    Item? collision;
    try {
      collision = await widget.cubit.findCollisionOnTarget(widget.item, targetListId);
    } on Object {
      // A failed pre-check falls back to the clean-move path — the server's process-manager race
      // safety net (Cl. 3) swallows a stale collision as convergent success either way.
      collision = null;
    }
    if (!mounted) {
      return;
    }
    navigator.pop();
    if (collision == null) {
      widget.cubit.moveItem(widget.item.itemId, targetListId);
    } else {
      // Anchor the merge sheet on the Navigator's own context — this sheet's context was just popped.
      showMoveMergeDialog(
        navigator.context,
        cubit: widget.cubit,
        sourceItem: widget.item,
        targetItem: collision,
        targetListId: targetListId,
        targetListName: targetListName,
      );
    }
  }

  Future<void> _createAndSelectNewList() async {
    final name = await showModalBottomSheet<String>(
      context: context,
      isScrollControlled: true,
      builder: (dialogContext) => const _NewListNameSheetBody(),
    );
    if (name == null || !mounted) {
      return;
    }
    final navigator = Navigator.of(context);
    final trimmedName = name.trim();
    final listId = const Uuid().v4();
    try {
      await widget.shoppingListsApi.createList(
        widget.householdId,
        name: trimmedName.isEmpty ? null : trimmedName,
        listId: listId,
        commandId: const Uuid().v4(),
      );
    } on Object {
      return; // the target sheet stays open; the member can retry "Neue Liste"
    }
    if (!mounted) {
      return;
    }
    navigator.pop();
    // A brand-new list is always empty — never collides, so this is always the clean-move path.
    widget.cubit.moveItem(widget.item.itemId, listId);
  }

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return Padding(
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
          Text(localizations.itemMoveTargetHeading, style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: SgartShapes.space4),
          switch (_status) {
            _LoadStatus.loading => const Padding(
                padding: EdgeInsets.all(SgartShapes.cardPadding),
                child: Center(child: CircularProgressIndicator(key: Key('move-target-loading'))),
              ),
            _LoadStatus.failure =>
              Text(localizations.errorGenericFallback, key: const Key('move-target-error')),
            _LoadStatus.ready => _targets.isEmpty
                ? Text(localizations.itemMoveTargetEmptyState, key: const Key('move-target-empty-state'))
                : Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      for (final (orderIndex, target) in _targets)
                        ListTile(
                          key: Key('move-target-row-${target.listId}'),
                          contentPadding: EdgeInsets.zero,
                          title: Text(target.name ?? localizations.listsDefaultName(orderIndex)),
                          onTap: () =>
                              _selectTarget(target.listId, target.name ?? localizations.listsDefaultName(orderIndex)),
                        ),
                    ],
                  ),
          },
          const SizedBox(height: SgartShapes.space4),
          SgartButton(
            key: const Key('move-target-new-list-button'),
            label: localizations.listsCreateAction,
            onPressed: _createAndSelectNewList,
          ),
        ],
      ),
    );
  }
}
