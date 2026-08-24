import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/errors/error_message_resolver.dart';
import '../../../shared/widgets/sgart_app_bar.dart';
import '../../../shared/widgets/sgart_button.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import '../data/household_summary.dart';
import '../data/households_api.dart';
import 'households_cubit.dart';
import 'rename_household_cubit.dart';
import 'rename_household_state.dart';

/// The rename-household form (AC3): a name field prefilled with the current name. On success it
/// propagates the new name to the shell (so header/switcher/home all update), pops back, and shows
/// a brief confirmation. Rename is offered to the current member; the backend enforces Admin-only
/// (every current member is an Admin — Clarification C) and a rejection surfaces inline.
class RenameHouseholdPage extends StatelessWidget {
  const RenameHouseholdPage({super.key, required this.household});

  final HouseholdSummary household;

  @override
  Widget build(BuildContext context) {
    return BlocProvider(
      create: (_) => RenameHouseholdCubit(
        householdsApi: context.read<HouseholdsApi>(),
        householdId: household.householdId,
      ),
      child: _RenameHouseholdView(householdId: household.householdId, currentName: household.name),
    );
  }
}

class _RenameHouseholdView extends StatefulWidget {
  const _RenameHouseholdView({required this.householdId, required this.currentName});

  final String householdId;
  final String currentName;

  @override
  State<_RenameHouseholdView> createState() => _RenameHouseholdViewState();
}

class _RenameHouseholdViewState extends State<_RenameHouseholdView> {
  late final TextEditingController _nameController = TextEditingController(text: widget.currentName);

  @override
  void dispose() {
    _nameController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return BlocListener<RenameHouseholdCubit, RenameHouseholdState>(
      listener: (context, state) {
        if (state.status == RenameHouseholdStatus.success) {
          // Update the name everywhere it is shown (AC3), then pop and confirm on the shell.
          context.read<HouseholdsCubit>().applyHouseholdRename(widget.householdId, state.newName!);
          final messenger = ScaffoldMessenger.of(context);
          // Pop just this rename route back to the shell; a single pop mirrors the switch flow and
          // won't tear down any intermediate route a future create→rename chain might push.
          Navigator.of(context).pop();
          messenger.showSnackBar(SnackBar(
            key: const Key('rename-confirmation'),
            content: Text(localizations.householdsRenameSuccessConfirmation),
          ));
        }
      },
      child: Scaffold(
        appBar: SgartAppBar(title: localizations.householdsRenameHeading),
        body: SafeArea(
          child: BlocBuilder<RenameHouseholdCubit, RenameHouseholdState>(
            builder: (context, state) {
              final isSubmitting = state.status == RenameHouseholdStatus.submitting;
              return SingleChildScrollView(
                padding: const EdgeInsets.all(SgartShapes.cardPadding),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.center,
                  children: [
                    TextField(
                      key: const Key('rename-household-name-field'),
                      controller: _nameController,
                      decoration: InputDecoration(labelText: localizations.householdsCreateNameFieldLabel),
                    ),
                    if (state.status == RenameHouseholdStatus.failure && state.error != null) ...[
                      const SizedBox(height: SgartShapes.space4),
                      Text(
                        localizedMessageForErrorCode(localizations, state.error!.code),
                        key: const Key('rename-household-error'),
                      ),
                    ],
                    const SizedBox(height: SgartShapes.space4),
                    SgartButton(
                      key: const Key('rename-household-submit-button'),
                      label: localizations.householdsRenameSubmitButtonLabel,
                      onPressed: isSubmitting
                          ? null
                          : () => context.read<RenameHouseholdCubit>().submit(_nameController.text),
                    ),
                  ],
                ),
              );
            },
          ),
        ),
      ),
    );
  }
}
