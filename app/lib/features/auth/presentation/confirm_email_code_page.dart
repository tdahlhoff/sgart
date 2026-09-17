import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/errors/error_message_resolver.dart';
import '../../../shared/widgets/sgart_app_bar.dart';
import '../../../shared/widgets/sgart_button.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import 'account_email_cubit.dart';
import 'account_email_state.dart';

/// Pushes [ConfirmEmailCodePage], re-providing the same [AccountEmailCubit] instance
/// [AddRecoveryEmailPage] built. Returns `true` once the code was confirmed (a genuine
/// `AccountEmailStatus.confirmed` transition), `false`/`null` otherwise (backed out, or the page
/// popped without success) — the caller uses that to decide whether to pop itself too.
Future<bool?> openConfirmEmailCodePage(BuildContext context, AccountEmailCubit accountEmailCubit) {
  return Navigator.of(context).push<bool>(
    MaterialPageRoute<bool>(
      builder: (_) => BlocProvider<AccountEmailCubit>.value(
        value: accountEmailCubit,
        child: const ConfirmEmailCodePage(),
      ),
    ),
  );
}

/// Enter-code step of Story 7.3's attach flow (AC1): submits to [AccountEmailCubit.confirm] and
/// pops itself with `true` once the email is confirmed. A wrong/expired/exhausted code is shown
/// **inline** and never tears down anything (the 7.2 Review Finding #1 lesson, reapplied here even
/// though there is no app-wide session at stake in the attach path — same discipline, same page
/// shape as [RecoverByEmailPage]).
class ConfirmEmailCodePage extends StatefulWidget {
  const ConfirmEmailCodePage({super.key});

  @override
  State<ConfirmEmailCodePage> createState() => _ConfirmEmailCodePageState();
}

class _ConfirmEmailCodePageState extends State<ConfirmEmailCodePage> {
  final _codeController = TextEditingController();

  @override
  void dispose() {
    _codeController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return BlocConsumer<AccountEmailCubit, AccountEmailState>(
      listenWhen: (previous, current) =>
          previous.status != AccountEmailStatus.confirmed && current.status == AccountEmailStatus.confirmed,
      listener: (context, state) => Navigator.of(context).pop(true),
      builder: (context, state) {
        return Scaffold(
          appBar: SgartAppBar(title: localizations.confirmEmailCodeTitle),
          body: SafeArea(
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(SgartShapes.cardPadding),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text(localizations.confirmEmailCodeSubtitle, key: const Key('confirm-email-code-subtitle')),
                  const SizedBox(height: SgartShapes.space4),
                  TextField(
                    key: const Key('confirm-email-code-field'),
                    controller: _codeController,
                    enabled: !state.isBusy,
                    keyboardType: TextInputType.number,
                    decoration: InputDecoration(labelText: localizations.confirmEmailCodeFieldLabel),
                  ),
                  if (state.error != null) ...[
                    const SizedBox(height: SgartShapes.space2),
                    Text(
                      localizedMessageForErrorCode(localizations, state.error!.code),
                      key: const Key('confirm-email-code-error'),
                    ),
                  ],
                  const SizedBox(height: SgartShapes.space4),
                  SgartButton(
                    key: const Key('confirm-email-code-submit-button'),
                    label: localizations.confirmEmailCodeSubmitButtonLabel,
                    onPressed: state.isBusy
                        ? null
                        : () => context.read<AccountEmailCubit>().confirm(_codeController.text.trim()),
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
