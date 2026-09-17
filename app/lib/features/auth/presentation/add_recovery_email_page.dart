import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/errors/error_message_resolver.dart';
import '../../../shared/http/authenticated_http_client.dart';
import '../../../shared/widgets/sgart_app_bar.dart';
import '../../../shared/widgets/sgart_button.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import 'account_email_cubit.dart';
import 'account_email_state.dart';
import 'confirm_email_code_page.dart';

/// Pushes [AddRecoveryEmailPage], building a fresh [AccountEmailCubit] over the ambient
/// [AuthenticatedHttpClient] (already provided by `FirstRunRouter`, reachable from the Profil
/// screen — mirrors every other API built from it, e.g. `HouseholdsApi`). Re-provided as the same
/// instance across the push into [ConfirmEmailCodePage] (the `openRecoveryPhraseRevealPage`
/// provider-escape precedent), and handed back to the caller on pop so the Profil section's own
/// [AccountEmailCubit] instance stays in sync with the attach/confirm outcome (CLAUDE.md §1 DRY —
/// one cubit instance for the whole attach→confirm round trip, not two disconnected ones).
Future<void> openAddRecoveryEmailPage(BuildContext context, AccountEmailCubit accountEmailCubit) {
  return Navigator.of(context).push(
    MaterialPageRoute<void>(
      builder: (_) => BlocProvider<AccountEmailCubit>.value(
        value: accountEmailCubit,
        child: const AddRecoveryEmailPage(),
      ),
    ),
  );
}

/// Enter-email step of Story 7.3's attach flow (AC1): submits to
/// [AccountEmailCubit.attach], then — once the cubit reaches `pendingConfirmation` — pushes
/// [ConfirmEmailCodePage]. Forgiving input (trimmed) and an inline, fail-fast error, mirroring
/// [RecoverAccountPage]'s shape.
class AddRecoveryEmailPage extends StatefulWidget {
  const AddRecoveryEmailPage({super.key});

  @override
  State<AddRecoveryEmailPage> createState() => _AddRecoveryEmailPageState();
}

class _AddRecoveryEmailPageState extends State<AddRecoveryEmailPage> {
  final _emailController = TextEditingController();

  @override
  void dispose() {
    _emailController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return BlocConsumer<AccountEmailCubit, AccountEmailState>(
      // Fires on every successful `attach()` completion (isBusy going true → false, landing on
      // `pendingConfirmation` with no error) — not only the very first one. Gating on a
      // status *transition* (the earlier shape) dead-ended a re-request: backing out of
      // `ConfirmEmailCodePage` leaves the cubit already in `pendingConfirmation`, so a second
      // `attach()` (a fresh code was sent) started and ended on the same status and never
      // re-triggered the listener (Story 7.3 review finding).
      listenWhen: (previous, current) =>
          previous.isBusy &&
          !current.isBusy &&
          current.status == AccountEmailStatus.pendingConfirmation &&
          current.error == null,
      listener: (context, state) async {
        final confirmed = await openConfirmEmailCodePage(context, context.read<AccountEmailCubit>());
        // Only a genuine confirm success pops this page too — backing out of the code step (or
        // it failing) leaves the person here, still able to retry the email step.
        if (confirmed == true && context.mounted) Navigator.of(context).pop();
      },
      builder: (context, state) {
        return Scaffold(
          appBar: SgartAppBar(title: localizations.addRecoveryEmailTitle),
          body: SafeArea(
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(SgartShapes.cardPadding),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text(localizations.addRecoveryEmailSubtitle, key: const Key('add-recovery-email-subtitle')),
                  const SizedBox(height: SgartShapes.space4),
                  TextField(
                    key: const Key('add-recovery-email-field'),
                    controller: _emailController,
                    enabled: !state.isBusy,
                    keyboardType: TextInputType.emailAddress,
                    decoration: InputDecoration(labelText: localizations.addRecoveryEmailFieldLabel),
                  ),
                  if (state.error != null) ...[
                    const SizedBox(height: SgartShapes.space2),
                    Text(
                      localizedMessageForErrorCode(localizations, state.error!.code),
                      key: const Key('add-recovery-email-error'),
                    ),
                  ],
                  const SizedBox(height: SgartShapes.space4),
                  SgartButton(
                    key: const Key('add-recovery-email-submit-button'),
                    label: localizations.addRecoveryEmailSubmitButtonLabel,
                    onPressed: state.isBusy
                        ? null
                        : () => context.read<AccountEmailCubit>().attach(_emailController.text.trim()),
                  ),
                ],
              ),
            ),
          ),
        );
      },
    );
  }
}
