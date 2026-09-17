import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/errors/app_error.dart';
import '../../../shared/errors/error_message_resolver.dart';
import '../../../shared/http/app_exception.dart';
import '../../../shared/http/authenticated_http_client.dart';
import '../../../shared/widgets/sgart_app_bar.dart';
import '../../../shared/widgets/sgart_button.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import '../data/account_email_api.dart';
import '../data/device_credential_store.dart';
import 'auth_cubit.dart';
import 'auth_state.dart';
import 'recovery_phrase_reveal_page.dart';

/// Pushes [RecoverByEmailPage] from the guaranteed 0-household gateway
/// (`CreateOrAwaitChoicePage`, peer to the phrase-based `RecoverAccountPage`), re-providing
/// everything the page and its eventual fresh-phrase reveal need across the root-Navigator push
/// boundary (the `openRecoveryPhraseRevealPage`/`_openRecoverAccount` precedent): [AuthCubit] (for
/// the post-rebind identity swap), [DeviceCredentialStore] (for the reveal page), and a fresh
/// [AccountEmailApi] over the ambient [AuthenticatedHttpClient].
void openRecoverByEmailPage(BuildContext context) {
  final authCubit = context.read<AuthCubit>();
  final deviceCredentialStore = context.read<DeviceCredentialStore>();
  final accountEmailApi = HttpAccountEmailApi(context.read<AuthenticatedHttpClient>());
  Navigator.of(context).push(
    MaterialPageRoute<void>(
      builder: (_) => MultiRepositoryProvider(
        providers: [
          RepositoryProvider<DeviceCredentialStore>.value(value: deviceCredentialStore),
          RepositoryProvider<AccountEmailApi>.value(value: accountEmailApi),
        ],
        child: BlocProvider<AuthCubit>.value(
          value: authCubit,
          child: const RecoverByEmailPage(),
        ),
      ),
    ),
  );
}

/// Email → code → rebind, Story 7.3's AC2/AC3 browserless recovery path on a fresh device. Two
/// local steps (this page owns its own state, not a cubit — mirrors [RecoverAccountPage]):
///
/// 1. Enter the email and request a code (`AccountEmailApi.requestRecoveryCode`) — the server
///    never reveals whether it matched an account (D-H), so this step always "succeeds" onward.
/// 2. Enter the code and confirm (`AccountEmailApi.confirmRecovery`); on `204` the server has
///    already performed the R1 rebind (design §1.1). Only then does this page reach into the auth
///    seam via [AuthCubit.recoverFromEmailRebind] — a wrong/expired/exhausted code at either step
///    is shown **inline** and never touches [AuthCubit] (the 7.2 Review Finding #1 discipline: the
///    underlying (throwaway) session must survive a local input error untouched).
class RecoverByEmailPage extends StatefulWidget {
  const RecoverByEmailPage({super.key});

  @override
  State<RecoverByEmailPage> createState() => _RecoverByEmailPageState();
}

enum _RecoverByEmailStep { enterEmail, enterCode }

class _RecoverByEmailPageState extends State<RecoverByEmailPage> {
  final _emailController = TextEditingController();
  final _codeController = TextEditingController();

  _RecoverByEmailStep _step = _RecoverByEmailStep.enterEmail;
  bool _isBusy = false;
  AppError? _localError;
  String _confirmedEmail = '';

  @override
  void dispose() {
    _emailController.dispose();
    _codeController.dispose();
    super.dispose();
  }

  Future<void> _requestCode() async {
    setState(() {
      _isBusy = true;
      _localError = null;
    });
    final email = _emailController.text.trim();
    try {
      await context.read<AccountEmailApi>().requestRecoveryCode(email);
      if (mounted) {
        setState(() {
          _confirmedEmail = email;
          _step = _RecoverByEmailStep.enterCode;
        });
      }
    } on Object catch (error) {
      if (mounted) setState(() => _localError = _toAppError(error));
    } finally {
      if (mounted) setState(() => _isBusy = false);
    }
  }

  /// On `204`, the server already performed the rebind — only *then* does this reach into the auth
  /// seam. A wrong/expired/exhausted code stays entirely local (never emitted to [AuthCubit]),
  /// leaving the current (throwaway) session mounted and untouched.
  Future<void> _confirmAndRebind(AuthCubit authCubit) async {
    setState(() {
      _isBusy = true;
      _localError = null;
    });
    try {
      await context.read<AccountEmailApi>().confirmRecovery(_confirmedEmail, _codeController.text.trim());
      await authCubit.recoverFromEmailRebind();
      // A successful rebind resolves into AuthState.authenticated, which the BlocListener below
      // catches to swap this route for the fresh-phrase reveal. Nothing further to do here.
    } on Object catch (error) {
      if (mounted) setState(() => _localError = _toAppError(error));
    } finally {
      if (mounted) setState(() => _isBusy = false);
    }
  }

  AppError _toAppError(Object error) {
    if (error is AppException) {
      return error.error;
    }
    return AppError(code: 'auth.unknown', message: error.toString());
  }

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);
    final deviceCredentialStore = context.read<DeviceCredentialStore>();

    return BlocListener<AuthCubit, AuthState>(
      // Only a genuinely new `authenticated` state (the recovered identity) fires this — mirrors
      // RecoverAccountPage's listenWhen. A single pushReplacement swaps this route for the reveal
      // page in one operation (no pop-then-push race against the AuthGate rebuild beneath).
      listenWhen: (previous, current) => current.status == AuthStatus.authenticated,
      listener: (context, state) {
        Navigator.of(context).pushReplacement(
          MaterialPageRoute<void>(
            builder: (_) => RepositoryProvider<DeviceCredentialStore>.value(
              value: deviceCredentialStore,
              child: const RecoveryPhraseRevealPage(isFreshAfterEmailRecovery: true),
            ),
          ),
        );
      },
      child: Scaffold(
        appBar: SgartAppBar(title: localizations.recoverByEmailTitle),
        body: SafeArea(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(SgartShapes.cardPadding),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                if (_step == _RecoverByEmailStep.enterEmail) ..._emailStep(localizations),
                if (_step == _RecoverByEmailStep.enterCode) ..._codeStep(localizations),
              ],
            ),
          ),
        ),
      ),
    );
  }

  List<Widget> _emailStep(AppLocalizations localizations) {
    return [
      Text(localizations.recoverByEmailEmailStepSubtitle, key: const Key('recover-by-email-email-subtitle')),
      const SizedBox(height: SgartShapes.space4),
      TextField(
        key: const Key('recover-by-email-email-field'),
        controller: _emailController,
        enabled: !_isBusy,
        keyboardType: TextInputType.emailAddress,
        decoration: InputDecoration(labelText: localizations.recoverByEmailFieldLabel),
      ),
      if (_localError != null) ..._errorText(localizations),
      const SizedBox(height: SgartShapes.space4),
      SgartButton(
        key: const Key('recover-by-email-request-button'),
        label: localizations.recoverByEmailRequestButtonLabel,
        onPressed: _isBusy ? null : _requestCode,
      ),
    ];
  }

  List<Widget> _codeStep(AppLocalizations localizations) {
    return [
      Text(localizations.recoverByEmailCodeStepSubtitle, key: const Key('recover-by-email-code-subtitle')),
      const SizedBox(height: SgartShapes.space4),
      TextField(
        key: const Key('recover-by-email-code-field'),
        controller: _codeController,
        enabled: !_isBusy,
        keyboardType: TextInputType.number,
        decoration: InputDecoration(labelText: localizations.recoverByEmailCodeFieldLabel),
      ),
      if (_localError != null) ..._errorText(localizations),
      const SizedBox(height: SgartShapes.space4),
      SgartButton(
        key: const Key('recover-by-email-confirm-button'),
        label: localizations.recoverByEmailConfirmButtonLabel,
        onPressed: _isBusy ? null : () => _confirmAndRebind(context.read<AuthCubit>()),
      ),
    ];
  }

  List<Widget> _errorText(AppLocalizations localizations) {
    return [
      const SizedBox(height: SgartShapes.space2),
      Text(
        localizedMessageForErrorCode(localizations, _localError!.code),
        key: const Key('recover-by-email-error'),
      ),
    ];
  }
}
