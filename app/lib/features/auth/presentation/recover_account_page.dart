import 'package:flutter/material.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/errors/error_message_resolver.dart';
import '../../../shared/widgets/sgart_app_bar.dart';
import '../../../shared/widgets/sgart_button.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import '../data/recovery_phrase.dart';
import 'auth_cubit.dart';
import 'auth_state.dart';

/// The 24-word recovery-phrase entry form (Story 7.2, AC3, D-D) — the only recovery entry point: a
/// person auto-provisioned into a fresh, empty throwaway account (Story 7.1) lands on the choice
/// screen with zero households, and Profil is unreachable until then, so the choice screen is the
/// only place this can live.
///
/// On a valid phrase, [AuthCubit.recoverFromPhrase] imports the entropy and re-signs-in as the
/// *existing* account behind it (the identity swap, D-E). This page pops itself once that swap
/// lands (a genuinely new `authenticated` state), letting the `AuthGate` subtree underneath show
/// the recovered identity's households. An invalid phrase is rejected **inline** and changes
/// nothing — the validation runs before any global auth-state change, so the underlying session is
/// never torn down (AC3 "changes nothing"; code review 2026-09-14). Pure native UI, no browser.
class RecoverAccountPage extends StatefulWidget {
  const RecoverAccountPage({super.key});

  @override
  State<RecoverAccountPage> createState() => _RecoverAccountPageState();
}

class _RecoverAccountPageState extends State<RecoverAccountPage> {
  final _phraseController = TextEditingController();

  /// True while the phrase is validated + imported, before the global sign-in phase begins. Kept
  /// local so an invalid phrase never drives the app-wide [AuthCubit] to `inProgress`/`failure` —
  /// that would unmount the underlying session (AC3).
  bool _isImporting = false;

  /// The error code of a *local* failure — an invalid phrase, or the rare pre-sign-in import
  /// failure — resolved to copy via [localizedMessageForErrorCode]. Distinct from a sign-in-phase
  /// failure, which is surfaced from the global [AuthState] below.
  String? _localErrorCode;

  @override
  void dispose() {
    _phraseController.dispose();
    super.dispose();
  }

  /// Forgiving normalization before validation (Dev Notes: "keep it forgiving"): trims, lowercases,
  /// and collapses whitespace/newlines so pasted or awkwardly-spaced input still parses into the 24
  /// words. The actual BIP39 validation (word count, wordlist membership, checksum) happens once —
  /// in `RecoveryPhrase.entropyFromWords`, via [AuthCubit.recoverFromPhrase] — no duplicate rule
  /// here.
  List<String> _normalizedWords() =>
      _phraseController.text.trim().toLowerCase().split(RegExp(r'\s+')).where((word) => word.isNotEmpty).toList();

  /// Imports the phrase and lets [AuthCubit.recoverFromPhrase] drive the identity swap through the
  /// global auth state. An invalid phrase throws [InvalidRecoveryPhrase] *before* any global state
  /// change, so it is shown inline here and the current session is left untouched.
  Future<void> _submit(AuthCubit authCubit) async {
    setState(() {
      _isImporting = true;
      _localErrorCode = null;
    });
    try {
      await authCubit.recoverFromPhrase(_normalizedWords());
      // A valid phrase drove the swap through the global auth state: on success the BlocListener
      // pops this page; a sign-in failure is rendered from the AuthState below. Nothing to do here.
    } on InvalidRecoveryPhrase {
      if (mounted) setState(() => _localErrorCode = 'auth.invalidRecoveryPhrase');
    } on Object {
      // A non-phrase failure before the sign-in phase (e.g. a secure-storage write error). The
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
        appBar: SgartAppBar(title: localizations.recoveryPhraseRestoreTitle),
        body: SafeArea(
          child: BlocBuilder<AuthCubit, AuthState>(
            builder: (context, state) {
              final isBusy = _isImporting || state.status == AuthStatus.inProgress;
              // A failure emitted by the sign-in phase after a *valid* import (e.g. the server was
              // unreachable). The invalid-phrase case is handled locally via [_localErrorCode], so
              // it never reaches this global-state branch.
              final signInError = state.status == AuthStatus.failure ? state.error : null;

              return SingleChildScrollView(
                padding: const EdgeInsets.all(SgartShapes.cardPadding),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Text(
                      localizations.recoveryPhraseRestoreSubtitle,
                      key: const Key('recover-account-subtitle'),
                    ),
                    const SizedBox(height: SgartShapes.space4),
                    TextField(
                      key: const Key('recover-account-phrase-field'),
                      controller: _phraseController,
                      maxLines: 4,
                      enabled: !isBusy,
                      decoration: InputDecoration(labelText: localizations.recoveryPhraseRestoreFieldLabel),
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
                      label: localizations.recoveryPhraseRestoreSubmitButtonLabel,
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
