import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/errors/error_message_resolver.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import 'nickname_cubit.dart';
import 'nickname_state.dart';

/// The shared nickname input: the labeled field bound to [controller] plus the inline error shown
/// when [NicknameCubit] rejects it (`nickname.required` / `nickname.tooLong`, client- or
/// server-side). Both the onboarding nickname step (create and join entry points) and the Profile
/// edit affordance embed this (CLAUDE.md §1 DRY).
class NicknameField extends StatelessWidget {
  const NicknameField({super.key, required this.controller, required this.fieldKey, required this.errorKey});

  final TextEditingController controller;
  final Key fieldKey;
  final Key errorKey;

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return BlocBuilder<NicknameCubit, NicknameState>(
      builder: (context, state) {
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            TextField(
              key: fieldKey,
              controller: controller,
              decoration: InputDecoration(labelText: localizations.nicknameFieldLabel),
            ),
            if (state.status == NicknameStatus.failure && state.error != null) ...[
              const SizedBox(height: SgartShapes.space4),
              Text(localizedMessageForErrorCode(localizations, state.error!.code), key: errorKey),
            ],
          ],
        );
      },
    );
  }
}
