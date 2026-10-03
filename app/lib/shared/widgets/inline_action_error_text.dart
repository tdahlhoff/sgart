import 'package:flutter/material.dart';

import '../../l10n/gen/app_localizations.dart';
import '../../theme/sgart_theme_access.dart';
import '../../theme/tokens/sgart_shapes.dart';
import '../errors/app_error.dart';
import '../errors/error_message_resolver.dart';

/// The compact way for a screen to report a rejected action: one line of small error-coloured text,
/// for places where space is tight and the message belongs right next to the input that caused it
/// (the fast-add field). Carries no vertical spacing — the caller knows what sits above and below
/// and spaces it. Renders nothing while [error] is `null`. For errors that deserve to stand
/// out — a tinted, animated, dismissible box — use `ActionErrorBanner` instead.
///
/// The text uses `onErrorTint`, not the raw pink: pink is a fill colour and fails AA as text
/// (DESIGN §1).
class InlineActionErrorText extends StatelessWidget {
  const InlineActionErrorText({super.key, required this.error, this.textKey = const Key('item-list-action-error')});

  final AppError? error;

  /// The key of the message text, so each screen can keep the key its tests and tooling know.
  final Key textKey;

  @override
  Widget build(BuildContext context) {
    final shownError = error;
    if (shownError == null) {
      return const SizedBox.shrink();
    }
    final message = localizedMessageForErrorCode(AppLocalizations.of(context), shownError.code);
    final bodySmall = Theme.of(context).textTheme.bodySmall;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: SgartShapes.cardPadding),
      child: Semantics(
        liveRegion: true,
        label: message,
        excludeSemantics: true,
        child: Text(
          message,
          key: textKey,
          style: bodySmall?.copyWith(color: context.sgartColors.onErrorTint),
        ),
      ),
    );
  }
}
