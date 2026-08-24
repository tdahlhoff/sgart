import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/errors/error_message_resolver.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import 'create_household_cubit.dart';
import 'create_household_state.dart';

/// The shared create-household name input: the labeled name field bound to [controller] plus the
/// inline create error shown when the reused [CreateHouseholdCubit] rejects the name
/// (`household.nameRequired` / `household.nameTooLong`). Both the minimal `CreateHouseholdPage` and
/// the onboarding wizard's name step (Story 1.9) embed this, so the field + error rendering has a
/// single representation (CLAUDE.md §1 DRY); each host keeps its own chrome and submit affordance.
class CreateHouseholdNameField extends StatelessWidget {
  const CreateHouseholdNameField({
    super.key,
    required this.controller,
    required this.fieldKey,
    required this.errorKey,
    this.helper,
    this.readOnly = false,
  });

  final TextEditingController controller;
  final Key fieldKey;
  final Key errorKey;

  /// Optional copy shown between the field and the error — the onboarding wizard's „… später ändern"
  /// reassurance; `null` for the minimal create page.
  final Widget? helper;

  /// When `true` the name can be read but not edited — used once the wizard has already created the
  /// household, so revisiting the name step cannot diverge the shown name from the persisted one.
  final bool readOnly;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return BlocBuilder<CreateHouseholdCubit, CreateHouseholdState>(
      builder: (context, state) {
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            TextField(
              key: fieldKey,
              controller: controller,
              readOnly: readOnly,
              decoration: InputDecoration(labelText: localizations.householdsCreateNameFieldLabel),
            ),
            if (helper != null) ...[
              const SizedBox(height: SgartShapes.space3),
              helper!,
            ],
            if (state.status == CreateHouseholdStatus.failure && state.error != null) ...[
              const SizedBox(height: SgartShapes.space4),
              Text(
                localizedMessageForErrorCode(localizations, state.error!.code),
                key: errorKey,
              ),
            ],
          ],
        );
      },
    );
  }
}
