import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/errors/error_message_resolver.dart';
import '../../../shared/widgets/sgart_app_bar.dart';
import '../../../shared/widgets/sgart_button.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import '../data/recovery_token.dart';
import 'auth_cubit.dart';
import 'auth_state.dart';

/// The recovery-token entry form (Story 7.2, AC3, D-D; token format Story 8.5, D1) — the only
/// recovery entry point: a person auto-provisioned into a fresh, empty throwaway account (Story
/// 7.1) lands on the choice screen with zero households, and Profil is unreachable until then, so
/// the choice screen is the only place this can live.
///
/// On a valid token, [AuthCubit.recoverFromToken] imports the entropy and re-signs-in as the
/// *existing* account behind it (the identity swap, D-E). This page pops itself once that swap
/// lands (a genuinely new `authenticated` state), letting the `AuthGate` subtree underneath show
/// the recovered identity's households. An invalid token is rejected **inline** and changes
/// nothing — the validation runs before any global auth-state change, so the underlying session is
/// never torn down (AC3 "changes nothing"; code review 2026-09-14). Pure native UI, no browser.
class RecoverAccountPage extends StatefulWidget {
  const RecoverAccountPage({super.key});

  @override
  State<RecoverAccountPage> createState() => _RecoverAccountPageState();
}

class _RecoverAccountPageState extends State<RecoverAccountPage> {
  final _tokenController = TextEditingController();

  /// True while the token is validated + imported, before the global sign-in phase begins. Kept
  /// local so an invalid token never drives the app-wide [AuthCubit] to `inProgress`/`failure` —
  /// that would unmount the underlying session (AC3).
  bool _isImporting = false;

  /// The error code of a *local* failure — an invalid token, or the rare pre-sign-in import
  /// failure — resolved to copy via [localizedMessageForErrorCode]. Distinct from a sign-in-phase
  /// failure, which is surfaced from the global [AuthState] below.
  String? _localErrorCode;

  @override
  void dispose() {
    _tokenController.dispose();
    super.dispose();
  }

  /// Imports the token and lets [AuthCubit.recoverFromToken] drive the identity swap through the
  /// global auth state. An invalid token throws [InvalidRecoveryToken] *before* any global state
  /// change, so it is shown inline here and the current session is left untouched. Forgiving input
  /// (case, spaces, missing hyphens, Crockford aliases) is handled once, inside
  /// [RecoveryToken.parse] — no duplicate normalization here.
  Future<void> _submit(AuthCubit authCubit) async {
    setState(() {
      _isImporting = true;
      _localErrorCode = null;
    });
    try {
      await authCubit.recoverFromToken(_tokenController.text);
      // A valid token drove the swap through the global auth state: on success the BlocListener
      // pops this page; a sign-in failure is rendered from the AuthState below. Nothing to do here.
    } on InvalidRecoveryToken {
      if (mounted) setState(() => _localErrorCode = 'auth.invalidRecoveryToken');
    } on Object {
      // A non-token failure before the sign-in phase (e.g. a secure-storage write error). The
      // global auth state was never touched, so surface it locally instead of failing silently.
      if (mounted) setState(() => _localErrorCode = 'auth.unknown');
    } finally {
      if (mounted) setState(() => _isImporting = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return BlocListener<AuthCubit, AuthState>(
      // Only a genuinely new `authenticated` state (the recovered identity, D-E) fires this — the
      // throwaway's own `authenticated` state (already current when this page opens) is never
      // redelivered to a listener that subscribes after the fact, so this can never pop on mount.
      listenWhen: (previous, current) => current.status == AuthStatus.authenticated,
      listener: (context, state) => Navigator.of(context).pop(),
      child: Scaffold(
        appBar: SgartAppBar(title: localizations.recoveryTokenRestoreTitle),
        body: SafeArea(
          child: BlocBuilder<AuthCubit, AuthState>(
            builder: (context, state) {
              final isBusy = _isImporting || state.status == AuthStatus.inProgress;
              // A failure emitted by the sign-in phase after a *valid* import (e.g. the server was
              // unreachable). The invalid-token case is handled locally via [_localErrorCode], so
              // it never reaches this global-state branch.
              final signInError = state.status == AuthStatus.failure ? state.error : null;

              return SingleChildScrollView(
                padding: const EdgeInsets.all(SgartShapes.cardPadding),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Text(
                      localizations.recoveryTokenRestoreSubtitle,
                      key: const Key('recover-account-subtitle'),
                    ),
                    const SizedBox(height: SgartShapes.space4),
                    TextField(
                      key: const Key('recover-account-token-field'),
                      controller: _tokenController,
                      enabled: !isBusy,
                      decoration: InputDecoration(labelText: localizations.recoveryTokenRestoreFieldLabel),
                    ),
                    if (_localErrorCode != null) ...[
                      const SizedBox(height: SgartShapes.space2),
                      Text(
                        localizedMessageForErrorCode(localizations, _localErrorCode!),
                        key: const Key('recover-account-error'),
                      ),
                    ],
                    if (signInError != null) ...[
                      const SizedBox(height: SgartShapes.space2),
                      Text(
                        localizedMessageForErrorCode(localizations, signInError.code),
                        key: const Key('recover-account-signin-error'),
                      ),
                    ],
                    const SizedBox(height: SgartShapes.space4),
                    SgartButton(
                      key: const Key('recover-account-submit-button'),
                      label: localizations.recoveryTokenRestoreSubmitButtonLabel,
                      onPressed: isBusy ? null : () => _submit(context.read<AuthCubit>()),
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
