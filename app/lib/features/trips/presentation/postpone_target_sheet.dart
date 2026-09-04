import 'package:flutter/material.dart';
import 'package:uuid/uuid.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/widgets/sgart_button.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import '../../lists/data/shopping_list_summary.dart';
import '../../lists/data/shopping_lists_api.dart';
import 'trip_cubit.dart';

/// Opens the postpone target picker (Story 3.3, AC3/AC4): offers „Hier vormerken" (postpone in
/// place), the household's Open lists (the current list excluded), and „＋ Neue Liste" (create then
/// postpone). The sheet mirrors [showMoveTargetSheet] but is scoped to in-trip postpone — it calls
/// [TripCubit.postponeInPlace] or [TripCubit.postponeToList] instead of the planning-move paths,
/// so the two sheets are separate rather than shared (DRY would obscure their distinct semantics).
void showPostponeTargetSheet(
  BuildContext context, {
  required TripCubit cubit,
  required ShoppingListsApi shoppingListsApi,
  required String itemId,
  required String householdId,
  required String sourceListId,
}) {
  showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    builder: (sheetContext) => _PostponeTargetSheetBody(
      cubit: cubit,
      shoppingListsApi: shoppingListsApi,
      itemId: itemId,
      householdId: householdId,
      sourceListId: sourceListId,
    ),
  );
}

enum _LoadStatus { loading, ready, failure }

class _PostponeTargetSheetBody extends StatefulWidget {
  const _PostponeTargetSheetBody({
    required this.cubit,
    required this.shoppingListsApi,
    required this.itemId,
    required this.householdId,
    required this.sourceListId,
  });

  final TripCubit cubit;
  final ShoppingListsApi shoppingListsApi;
  final String itemId;
  final String householdId;
  final String sourceListId;

  @override
  State<_PostponeTargetSheetBody> createState() => _PostponeTargetSheetBodyState();
}

class _PostponeTargetSheetBodyState extends State<_PostponeTargetSheetBody> {
  _LoadStatus _status = _LoadStatus.loading;

  /// True after a „＋ Neue Liste" creation failed — the sheet stays open and shows an inline error
  /// instead of silently swallowing the failure (Story 3.3 review fix).
  bool _createFailed = false;

  /// The other Open lists — In-Trip lists are excluded (postpone target must be OPEN, server 409).
  /// Ordinals are counted over the full enumeration so fallback names match the overview (mirrors
  /// move_target_sheet.dart's rationale).
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
            if (list.listId != widget.sourceListId && list.status == 'OPEN') (index + 1, list),
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

  Future<void> _postponeInPlace() async {
    Navigator.of(context).pop();
    widget.cubit.postponeInPlace(widget.itemId);
  }

  Future<void> _postponeToList(String targetListId) async {
    Navigator.of(context).pop();
    widget.cubit.postponeToList(widget.itemId, targetListId);
  }

  Future<void> _createAndPostponeToNewList() async {
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
    setState(() => _createFailed = false);
    try {
      await widget.shoppingListsApi.createList(
        widget.householdId,
        name: trimmedName.isEmpty ? null : trimmedName,
        listId: listId,
        commandId: const Uuid().v4(),
      );
    } on Object {
      if (mounted) {
        setState(() => _createFailed = true); // sheet stays open with a visible error; retry clears it
      }
      return;
    }
    if (!mounted) {
      return;
    }
    navigator.pop();
    widget.cubit.postponeToList(widget.itemId, listId);
  }

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return Padding(
      key: const Key('postpone-target-sheet'),
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
          Text(localizations.tripPostponeSheetTitle, style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: SgartShapes.space4),
          ListTile(
            key: const Key('postpone-target-in-place'),
            contentPadding: EdgeInsets.zero,
            title: Text(localizations.tripPostponeInPlace),
            onTap: _postponeInPlace,
          ),
          switch (_status) {
            _LoadStatus.loading => const Padding(
                padding: EdgeInsets.all(SgartShapes.cardPadding),
                child: Center(child: CircularProgressIndicator(key: Key('postpone-target-loading'))),
              ),
            _LoadStatus.failure =>
              Text(localizations.errorGenericFallback, key: const Key('postpone-target-error')),
            _LoadStatus.ready => _targets.isEmpty
                ? Text(localizations.tripPostponeEmptyTargets, key: const Key('postpone-target-empty-state'))
                : Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      for (final (orderIndex, target) in _targets)
                        ListTile(
                          key: Key('postpone-target-row-${target.listId}'),
                          contentPadding: EdgeInsets.zero,
                          title: Text(target.name ?? localizations.listsDefaultName(orderIndex)),
                          onTap: () => _postponeToList(target.listId),
                        ),
                    ],
                  ),
          },
          const SizedBox(height: SgartShapes.space4),
          SgartButton(
            key: const Key('postpone-target-new-list-button'),
            label: localizations.listsCreateAction,
            onPressed: _createAndPostponeToNewList,
          ),
          if (_createFailed)
            Padding(
              padding: const EdgeInsets.only(top: SgartShapes.space4),
              child: Text(
                localizations.tripPostponeCreateFailedError,
                key: const Key('postpone-target-create-error'),
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ),
        ],
      ),
    );
  }
}

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
            key: const Key('postpone-target-new-list-name-field'),
            controller: _nameController,
            autofocus: true,
            maxLength: 120,
            decoration: InputDecoration(hintText: localizations.listsCreateNameHint),
          ),
          const SizedBox(height: SgartShapes.space4),
          SgartButton(
            key: const Key('postpone-target-new-list-submit-button'),
            label: localizations.listsCreateSubmitButtonLabel,
            onPressed: () => Navigator.of(context).pop(_nameController.text),
          ),
        ],
      ),
    );
  }
}
