import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/widgets/sgart_button.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import 'shopping_lists_cubit.dart';
import 'shopping_lists_state.dart';

/// Opens the minimal rename-list sheet (Story 2.1, AC3): a single required name field prefilled
/// with [currentName]. Submit is disabled on a blank/whitespace name — a fail-fast client guard so
/// a pointless round-trip never reaches the server (mirroring the `list.nameRequired` server code).
/// The cubit is captured by the caller (never re-read from the sheet's own context).
void showRenameListSheet(
  BuildContext context,
  ShoppingListsCubit cubit, {
  required String listId,
  required String currentName,
}) {
  showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    builder: (sheetContext) => _RenameListSheetBody(cubit: cubit, listId: listId, currentName: currentName),
  );
}

class _RenameListSheetBody extends StatefulWidget {
  const _RenameListSheetBody({required this.cubit, required this.listId, required this.currentName});

  final ShoppingListsCubit cubit;
  final String listId;
  final String currentName;

  @override
  State<_RenameListSheetBody> createState() => _RenameListSheetBodyState();
}

class _RenameListSheetBodyState extends State<_RenameListSheetBody> {
  late final TextEditingController _nameController = TextEditingController(text: widget.currentName);

  @override
  void dispose() {
    _nameController.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final navigator = Navigator.of(context);
    // Pop only on success — a rejection (e.g. a Done list) keeps the sheet open while the error
    // shows inline on the list view; the cubit ignores a re-entrant call while submitting.
    final renamed = await widget.cubit.renameList(widget.listId, _nameController.text);
    if (renamed) {
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
          Text(localizations.listsRenameHeading, style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: SgartShapes.space4),
          TextField(
            key: const Key('rename-list-name-field'),
            controller: _nameController,
            autofocus: true,
            maxLength: 120,
            decoration: InputDecoration(labelText: localizations.listsRenameNameFieldLabel),
          ),
          const SizedBox(height: SgartShapes.space4),
          // Disabled while the field is blank — nothing to send, and the server would only reject
          // it with `list.nameRequired` on a pointless round-trip — and while a create/rename is
          // already in flight, so a double-submit can't overlap another action.
          BlocBuilder<ShoppingListsCubit, ShoppingListsState>(
            bloc: widget.cubit,
            builder: (context, state) => ValueListenableBuilder<TextEditingValue>(
              valueListenable: _nameController,
              builder: (context, value, _) {
                final isBlank = value.text.trim().isEmpty;
                return SgartButton(
                  key: const Key('rename-list-submit-button'),
                  label: localizations.listsRenameSubmitButtonLabel,
                  onPressed: (isBlank || state.isSubmitting) ? null : _submit,
                );
              },
            ),
          ),
        ],
      ),
    );
  }
}
