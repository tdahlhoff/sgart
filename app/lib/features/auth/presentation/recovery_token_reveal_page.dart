import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_bloc/flutter_bloc.dart';

import '../../../l10n/gen/app_localizations.dart';
import '../../../shared/platform/screen_capture_guard.dart';
import '../../../shared/widgets/sgart_app_bar.dart';
import '../../../theme/tokens/sgart_shapes.dart';
import '../../../theme/tokens/sgart_typography.dart';
import '../data/device_credential_store.dart';

/// Pushes the shared [RecoveryTokenRevealPage], re-providing [DeviceCredentialStore] across the
/// root-Navigator push boundary — the provider-escape lesson `CreateOrAwaitChoicePage._openOnboarding`
/// documents: the root Navigator sits above the caller's own ancestor providers, so a pushed route
/// would otherwise miss them entirely. One shared helper for both entry points — the choice screen's
/// "save" action (AC1) and Profil's re-view row (AC2) — mirroring [openAwaitInvitePage] (DRY,
/// CLAUDE.md §1/§8).
/// @param isFreshAfterEmailRecovery Story 7.3, AC3/design §1.1: `true` only right after a
///     successful email recovery's R1 rebind, where the reveal shows a *freshly issued* token and
///     must say so plainly — the previous token (deriving the old device's key) no longer works
///     (D-I: the recovery capability persists across recoveries, a specific token string does not).
void openRecoveryTokenRevealPage(BuildContext context, {bool isFreshAfterEmailRecovery = false}) {
  final deviceCredentialStore = context.read<DeviceCredentialStore>();
  Navigator.of(context).push(
    MaterialPageRoute<void>(
      builder: (_) => RepositoryProvider<DeviceCredentialStore>.value(
        value: deviceCredentialStore,
        child: RecoveryTokenRevealPage(isFreshAfterEmailRecovery: isFreshAfterEmailRecovery),
      ),
    ),
  );
}

/// Shows the device's short recovery token (Story 7.2, AC1/AC2, D-B; token format Story 8.5, D1) —
/// the single shared reveal surface reached from two entry points: the choice screen's "save"
/// action (the first moment a person can see it, D-A) and Profil's re-view row (any time after,
/// unlimited, D-C).
///
/// The token is read straight from [DeviceCredentialStore.recoveryToken] — secure local storage
/// only, never fetched from or sent to the server (AC1/AC2/AC4) — and this page makes no network
/// call of its own. A „Kopieren" button (Story 8.5, D2) copies it to the clipboard.
class RecoveryTokenRevealPage extends StatefulWidget {
  const RecoveryTokenRevealPage({super.key, this.isFreshAfterEmailRecovery = false});

  /// See [openRecoveryTokenRevealPage]'s parameter doc.
  final bool isFreshAfterEmailRecovery;

  @override
  State<RecoveryTokenRevealPage> createState() => _RecoveryTokenRevealPageState();
}

class _RecoveryTokenRevealPageState extends State<RecoveryTokenRevealPage> {
  late final Future<String> _tokenFuture;

  @override
  void initState() {
    super.initState();
    _tokenFuture = context.read<DeviceCredentialStore>().recoveryToken();
    // The token is the account secret — keep it out of screenshots and recordings while shown.
    unawaited(ScreenCaptureGuard.protect());
  }

  @override
  void dispose() {
    unawaited(ScreenCaptureGuard.release());
    super.dispose();
  }

  Future<void> _copyToken(String token) async {
    bool copySucceeded;
    try {
      await Clipboard.setData(ClipboardData(text: token));
      copySucceeded = true;
    } on PlatformException {
      copySucceeded = false;
    }
    if (!mounted) {
      return;
    }
    final localizations = AppLocalizations.of(context);
    final message =
        copySucceeded ? localizations.recoveryTokenCopiedSnackBar : localizations.recoveryTokenCopyFailedSnackBar;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
  }

  @override
  Widget build(BuildContext context) {
    final localizations = AppLocalizations.of(context);

    return Scaffold(
      appBar: SgartAppBar(title: localizations.recoveryTokenRevealTitle),
      body: SafeArea(
        child: FutureBuilder<String>(
          future: _tokenFuture,
          builder: (context, snapshot) {
            // Surface a read failure instead of hiding it behind a perpetual spinner (fail fast,
            // CLAUDE.md §1) — a secure-storage read can fail even though the entropy exists.
            if (snapshot.hasError) {
              return Center(
                child: Padding(
                  padding: const EdgeInsets.all(SgartShapes.cardPadding),
                  child: Text(
                    localizations.recoveryTokenRevealLoadError,
                    key: const Key('recovery-token-load-error'),
                    textAlign: TextAlign.center,
                  ),
                ),
              );
            }
            final token = snapshot.data;
            if (token == null) {
              return const Center(
                child: CircularProgressIndicator(key: Key('recovery-token-loading-indicator')),
              );
            }
            return ListView(
              padding: const EdgeInsets.all(SgartShapes.cardPadding),
              children: [
                Text(
                  widget.isFreshAfterEmailRecovery
                      ? localizations.recoveryTokenFreshAfterEmailRecoveryWarning
                      : localizations.recoveryTokenRevealWarning,
                  key: const Key('recovery-token-warning'),
                ),
                const SizedBox(height: SgartShapes.space4),
                Semantics(
                  label: localizations.recoveryTokenValueSemanticLabel(token),
                  child: ExcludeSemantics(
                    child: Wrap(
                      key: const Key('recovery-token-value'),
                      alignment: WrapAlignment.center,
                      spacing: SgartShapes.space2,
                      runSpacing: SgartShapes.spaceUnit,
                      // Splitting into one widget per hyphen-group means a narrow screen only ever
                      // wraps at a hyphen, never mid-group (a mid-group break makes the token
                      // harder to transcribe by hand).
                      children: [
                        for (final group in token.split('-'))
                          SelectableText(
                            group,
                            style: SgartTypography.withTabularFigures(
                              Theme.of(context).textTheme.titleLarge!,
                            ).copyWith(letterSpacing: 1.5),
                          ),
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: SgartShapes.space4),
                OutlinedButton.icon(
                  key: const Key('recovery-token-copy-button'),
                  onPressed: () => _copyToken(token),
                  icon: const Icon(Icons.copy),
                  label: Text(localizations.recoveryTokenCopyButtonLabel),
                ),
              ],
            );
          },
        ),
      ),
    );
  }
}
