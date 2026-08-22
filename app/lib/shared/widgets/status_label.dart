import 'package:flutter/material.dart';

import '../../theme/sgart_theme_access.dart';
import '../../theme/tokens/sgart_colors.dart';
import '../../theme/tokens/sgart_shapes.dart';
import '../../theme/tokens/sgart_typography.dart';

/// Semantic variants for the list-row status label (DESIGN §4b). One tint = one meaning.
enum StatusLabelVariant {
  /// Household role Admin — baltic tint.
  admin,

  /// Household role Mitglied and similar neutral attributes.
  neutral,

  /// Ausstehend / offline / pending — amber tint.
  pending,

  /// Store chain — verdigris tint.
  storeChain,
}

/// Dense, tinted, uppercase status label that sits on its **own line under a row's name**
/// (DESIGN §4b, UX-DR21). It is never a trailing pill: names keep full horizontal width. The
/// caller supplies the (already-localized) text; there is no „Du" self-marker.
///
/// The tint background is the semantic hue at [SgartColors.tintAlpha]; the text is that hue's
/// `on…Tint` token rather than the hue itself, because the saturated hue on its own tint falls
/// well below AA at this size (amber reaches only 2.1:1) — DESIGN §1 requires ≥ AA for text.
class StatusLabel extends StatelessWidget {
  const StatusLabel({
    super.key,
    required this.text,
    this.variant = StatusLabelVariant.neutral,
  });

  final String text;
  final StatusLabelVariant variant;

  @override
  Widget build(BuildContext context) {
    final colors = context.sgartColors;

    final (Color tintSource, Color foreground) = switch (variant) {
      StatusLabelVariant.admin => (colors.primary, colors.onPrimaryTint),
      StatusLabelVariant.neutral => (colors.textSecondary, colors.onNeutralTint),
      StatusLabelVariant.pending => (colors.warning, colors.onWarningTint),
      StatusLabelVariant.storeChain => (colors.success, colors.onSuccessTint),
    };

    return DecoratedBox(
      decoration: BoxDecoration(
        color: tintSource.withValues(alpha: SgartColors.tintAlpha),
        borderRadius: SgartShapes.pill,
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(
          horizontal: SgartShapes.space2,
          vertical: SgartShapes.spaceHalfUnit,
        ),
        child: Text(
          text.toUpperCase(),
          // Uppercasing is a visual treatment: assistive technology gets the original string,
          // which also keeps it readable when a screen reader would spell out an all-caps word.
          semanticsLabel: text,
          style: SgartTypography.kicker.copyWith(color: foreground),
          // No line cap: at large text scales the tag wraps rather than truncating (AC2).
          overflow: TextOverflow.visible,
        ),
      ),
    );
  }
}
