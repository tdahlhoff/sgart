import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/widgets/sgart_button.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import 'shopping_lists_cubit.dart';
import 'shopping_lists_state.dart';

/// Opens the minimal create-list sheet (Story 2.1, AC1): a single optional name field. The cubit is
/// captured by the caller (never re-read from the sheet's own context), mirroring the store
/// chain-picker sheet's pattern.
void showCreateListSheet(BuildContext context, ShoppingListsCubit cubit) {
  showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    builder: (sheetContext) => _CreateListSheetBody(cubit: cubit),
  );
}

class _CreateListSheetBody extends StatefulWidget {
  const _CreateListSheetBody({required this.cubit});

  final ShoppingListsCubit cubit;

  @override
  State<_CreateListSheetBody> createState() => _CreateListSheetBodyState();
}

class _CreateListSheetBodyState extends State<_CreateListSheetBody> {
  final TextEditingController _nameController = TextEditingController();

  @override
  void dispose() {
    _nameController.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final navigator = Navigator.of(context);
    // Pop only on success — a rejection keeps the sheet open (and the typed name) while the error
    // shows inline on the list view; the cubit itself ignores a re-entrant call while submitting.
    final created = await widget.cubit.createList(_nameController.text);
    if (created) {
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
          Text(localizations.listsCreateHeading, style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: SgartShapes.space4),
          TextField(
            key: const Key('create-list-name-field'),
            controller: _nameController,
            autofocus: true,
            maxLength: 120,
            decoration: InputDecoration(hintText: localizations.listsCreateNameHint),
          ),
          const SizedBox(height: SgartShapes.space4),
          // Disabled while a create/rename is in flight — prevents a double-submit appending the
          // same client-minted list id twice.
          BlocBuilder<ShoppingListsCubit, ShoppingListsState>(
            bloc: widget.cubit,
            builder: (context, state) => SgartButton(
              key: const Key('create-list-submit-button'),
              label: localizations.listsCreateSubmitButtonLabel,
              onPressed: state.isSubmitting ? null : _submit,
            ),
          ),
        ],
      ),
    );
  }
}
