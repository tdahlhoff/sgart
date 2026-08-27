import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/formatting/quantity_formatter.dart' as formatting;
import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/widgets/sgart_button.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import '../data/item.dart';
import 'list_detail_cubit.dart';
import 'list_detail_state.dart';

/// Opens the item form sheet (Story 2.3, AC1/AC3) — one sheet for both add and edit: name field,
/// amount field + unit dropdown (defaulting to `1 Stück`, Clarification 1), and an optional note
/// field. Pass [existingItem] to edit it in place; omit it to add a new item. The cubit is captured
/// by the caller (never re-read from the sheet's own context), mirroring `showCreateListSheet`.
void showItemFormSheet(BuildContext context, ListDetailCubit cubit, {Item? existingItem}) {
  showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    builder: (sheetContext) => _ItemFormSheetBody(cubit: cubit, existingItem: existingItem),
  );
}

class _ItemFormSheetBody extends StatefulWidget {
  const _ItemFormSheetBody({required this.cubit, this.existingItem});

  final ListDetailCubit cubit;
  final Item? existingItem;

  @override
  State<_ItemFormSheetBody> createState() => _ItemFormSheetBodyState();
}

class _ItemFormSheetBodyState extends State<_ItemFormSheetBody> {
  late final TextEditingController _nameController =
      TextEditingController(text: widget.existingItem?.name ?? '');
  late final TextEditingController _amountController =
      TextEditingController(text: widget.existingItem?.amount ?? '1');
  late final TextEditingController _noteController =
      TextEditingController(text: widget.existingItem?.note ?? '');
  late formatting.Unit _selectedUnit = _unitFromServerName(widget.existingItem?.unit) ?? formatting.Unit.piece;

  bool get _isEditing => widget.existingItem != null;

  static formatting.Unit? _unitFromServerName(String? serverName) {
    if (serverName == null) {
      return null;
    }
    for (final unit in formatting.Unit.values) {
      if (unit.name.toUpperCase() == serverName) {
        return unit;
      }
    }
    return null;
  }

  @override
  void dispose() {
    _nameController.dispose();
    _amountController.dispose();
    _noteController.dispose();
    super.dispose();
  }

  /// The amount as the backend expects it: the de-DE comma the user types (the whole UI renders
  /// `0,5 kg`) is normalised to the `.` decimal separator `BigDecimal` parses. Without this a German
  /// user could never enter a fractional quantity — the reason `Quantity` uses `BigDecimal` at all.
  String get _normalizedAmount => _amountController.text.trim().replaceAll(',', '.');

  /// A blank/non-numeric/non-positive amount is a pointless round-trip (the server would only reject
  /// it with `item.quantityRequired`/`item.quantityInvalid`) — guard it client-side like the name.
  bool get _isAmountValid {
    final parsed = double.tryParse(_normalizedAmount);
    return parsed != null && parsed > 0;
  }

  Future<void> _submit() async {
    final navigator = Navigator.of(context);
    final amount = _normalizedAmount;
    final unit = _selectedUnit.name.toUpperCase();
    final note = _noteController.text;
    // Pop only on success — a rejection keeps the sheet open (and the typed values) while the error
    // shows inline on the list detail view; the cubit itself ignores a re-entrant call while submitting.
    final succeeded = _isEditing
        ? await widget.cubit.updateItem(
            widget.existingItem!.itemId,
            name: _nameController.text,
            note: note,
            amount: amount,
            unit: unit,
          )
        : await widget.cubit.addItem(name: _nameController.text, note: note, amount: amount, unit: unit);
    if (succeeded) {
      navigator.pop();
    }
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
          Text(
            _isEditing ? localizations.itemEditHeading : localizations.itemAddHeading,
            style: Theme.of(context).textTheme.titleMedium,
          ),
          const SizedBox(height: SgartShapes.space4),
          TextField(
            key: const Key('item-name-field'),
            controller: _nameController,
            autofocus: true,
            maxLength: 120,
            decoration: InputDecoration(labelText: localizations.itemNameFieldLabel),
          ),
          const SizedBox(height: SgartShapes.space4),
          Row(
            children: [
              Expanded(
                child: TextField(
                  key: const Key('item-amount-field'),
                  controller: _amountController,
                  keyboardType: const TextInputType.numberWithOptions(decimal: true),
                  decoration: InputDecoration(labelText: localizations.itemAmountFieldLabel),
                ),
              ),
              const SizedBox(width: SgartShapes.space4),
              Expanded(
                child: DropdownButtonFormField<formatting.Unit>(
                  key: const Key('item-unit-dropdown'),
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
          TextField(
            key: const Key('item-note-field'),
            controller: _noteController,
            maxLength: 240,
            decoration: InputDecoration(labelText: localizations.itemNoteFieldLabel),
          ),
          const SizedBox(height: SgartShapes.space4),
          // Disabled while the name is blank or the amount is not a positive number (both are
          // pointless round-trips — the server would only reject them with item.nameRequired /
          // item.quantityInvalid) and while a submit is already in flight. Listens to both fields.
          BlocBuilder<ListDetailCubit, ListDetailState>(
            bloc: widget.cubit,
            builder: (context, state) => AnimatedBuilder(
              animation: Listenable.merge([_nameController, _amountController]),
              builder: (context, _) {
                final isNameBlank = _nameController.text.trim().isEmpty;
                final canSubmit = !isNameBlank && _isAmountValid && !state.isSubmitting;
                return SgartButton(
                  key: const Key('item-form-submit-button'),
                  label: _isEditing ? localizations.itemEditSubmitButtonLabel : localizations.itemAddSubmitButtonLabel,
                  onPressed: canSubmit ? _submit : null,
                );
              },
            ),
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
