import 'package:flutter/material.dart';

import '../../../l10n/formatting/number_formatter.dart';
import '../../../l10n/formatting/quantity_formatter.dart' as formatting;
import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/widgets/sgart_button.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import '../data/item.dart';
import 'list_detail_cubit.dart';

/// Opens the quantity-merge sheet (Story 2.4, AC4, Clarification 3): shown when the move target
/// already holds an item with the same (name, note) key as [sourceItem]. The target's existing
/// article is always kept — never a duplicate — so the sheet only asks whether to adjust its
/// quantity before the source is removed: „Menge aktualisieren & verschieben" applies the edited
/// amount + unit, „Unverändert übernehmen" leaves the target's quantity as-is; either way the
/// source item is removed. Dismissing the sheet (tap outside/swipe down) cancels — no cubit call.
/// The cubit is captured by the caller (never re-read from the sheet's own context), mirroring
/// `showItemFormSheet`.
void showMoveMergeDialog(
  BuildContext context, {
  required ListDetailCubit cubit,
  required Item sourceItem,
  required Item targetItem,
  required String targetListId,
  required String targetListName,
}) {
  showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    builder: (sheetContext) => _MoveMergeDialogBody(
      cubit: cubit,
      sourceItem: sourceItem,
      targetItem: targetItem,
      targetListId: targetListId,
      targetListName: targetListName,
    ),
  );
}

class _MoveMergeDialogBody extends StatefulWidget {
  const _MoveMergeDialogBody({
    required this.cubit,
    required this.sourceItem,
    required this.targetItem,
    required this.targetListId,
    required this.targetListName,
  });

  final ListDetailCubit cubit;
  final Item sourceItem;
  final Item targetItem;
  final String targetListId;
  final String targetListName;

  @override
  State<_MoveMergeDialogBody> createState() => _MoveMergeDialogBodyState();
}

class _MoveMergeDialogBodyState extends State<_MoveMergeDialogBody> {
  late final TextEditingController _amountController = TextEditingController(text: _initialAmountText());
  late formatting.Unit _selectedUnit = _unitFromServerName(widget.targetItem.unit) ?? formatting.Unit.piece;

  /// Pre-fills with the sum of source + target quantities when their units match (Cl. 3); otherwise
  /// the target's current quantity — the member edits from there if a sum makes no sense.
  String _initialAmountText() {
    final sourceAmount = double.tryParse(widget.sourceItem.amount);
    final targetAmount = double.tryParse(widget.targetItem.amount);
    final unitsMatch = widget.sourceItem.unit == widget.targetItem.unit;
    final amount = (unitsMatch && sourceAmount != null && targetAmount != null)
        ? sourceAmount + targetAmount
        : (targetAmount ?? 0);
    // No thousands grouping — this string pre-fills the amount field and is parsed back on confirm;
    // a grouping `.` (e.g. "1.600" for 1600) would otherwise be read as a decimal point.
    return const NumberFormatter(groupThousands: false).format(amount);
  }

  static formatting.Unit? _unitFromServerName(String serverName) {
    for (final unit in formatting.Unit.values) {
      if (unit.name.toUpperCase() == serverName) {
        return unit;
      }
    }
    return null;
  }

  @override
  void dispose() {
    _amountController.dispose();
    super.dispose();
  }

  /// The comma the German UI shows normalises to the `.` decimal separator the backend parses,
  /// exactly like `item_form_sheet`.
  String get _normalizedAmount => _amountController.text.trim().replaceAll(',', '.');

  bool get _isAmountValid {
    final parsed = double.tryParse(_normalizedAmount);
    return parsed != null && parsed > 0;
  }

  void _confirmWithAdjustment() {
    final navigator = Navigator.of(context);
    widget.cubit.mergeIntoTarget(
      widget.sourceItem.itemId,
      widget.targetListId,
      widget.targetItem,
      adjustedAmount: _normalizedAmount,
      adjustedUnit: _selectedUnit.name.toUpperCase(),
    );
    navigator.pop();
  }

  void _confirmUnchanged() {
    final navigator = Navigator.of(context);
    widget.cubit.mergeIntoTarget(widget.sourceItem.itemId, widget.targetListId, widget.targetItem);
    navigator.pop();
  }

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);
    final targetQuantityText = const formatting.QuantityFormatter().format(
      double.tryParse(widget.targetItem.amount) ?? 0,
      _unitFromServerName(widget.targetItem.unit) ?? formatting.Unit.piece,
      localizations,
    );

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
          Text(localizations.itemMoveMergeHeading, style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: SgartShapes.space4),
          Text(
            localizations.itemMoveMergeMessage(widget.sourceItem.name, widget.targetListName, targetQuantityText),
            key: const Key('move-merge-message'),
          ),
          const SizedBox(height: SgartShapes.space4),
          Row(
            children: [
              Expanded(
                child: TextField(
                  key: const Key('move-merge-amount-field'),
                  controller: _amountController,
                  keyboardType: const TextInputType.numberWithOptions(decimal: true),
                  decoration: InputDecoration(labelText: localizations.itemAmountFieldLabel),
                ),
              ),
              const SizedBox(width: SgartShapes.space4),
              Expanded(
                child: DropdownButtonFormField<formatting.Unit>(
                  key: const Key('move-merge-unit-dropdown'),
                  initialValue: _selectedUnit,
                  decoration: InputDecoration(labelText: localizations.itemUnitFieldLabel),
                  items: [
                    for (final unit in formatting.Unit.values)
                      DropdownMenuItem(value: unit, child: Text(_unitLabel(unit, localizations))),
                  ],
                  onChanged: (unit) {
                    if (unit != null) {
                      setState(() => _selectedUnit = unit);
                    }
                  },
                ),
              ),
            ],
          ),
          const SizedBox(height: SgartShapes.space4),
          AnimatedBuilder(
            animation: _amountController,
            builder: (context, _) => SgartButton(
              key: const Key('move-merge-confirm-button'),
              label: localizations.itemMoveMergeUpdateAction,
              onPressed: _isAmountValid ? _confirmWithAdjustment : null,
            ),
          ),
          const SizedBox(height: SgartShapes.space2),
          SgartButton(
            key: const Key('move-merge-unchanged-button'),
            label: localizations.itemMoveMergeKeepUnchangedAction,
            variant: SgartButtonVariant.secondary,
            onPressed: _confirmUnchanged,
          ),
        ],
      ),
    );
  }

  String _unitLabel(formatting.Unit unit, AppLocalizations localizations) => switch (unit) {
        formatting.Unit.piece => localizations.unitPiece,
        formatting.Unit.gram => localizations.unitGram,
        formatting.Unit.kilogram => localizations.unitKilogram,
        formatting.Unit.millilitre => localizations.unitMillilitre,
        formatting.Unit.litre => localizations.unitLitre,
        formatting.Unit.pack => localizations.unitPack,
      };
}
