import 'package:flutter/material.dart';

import '../../theme/sgart_theme_access.dart';
import '../../theme/tokens/sgart_colors.dart';
import '../../theme/tokens/sgart_shapes.dart';

/// The three button roles from DESIGN §4.
enum SgartButtonVariant {
  /// Filled baltic.
  primary,

  /// Outlined baltic on transparent.
  secondary,

  /// Quiet/terminal action — soft baltic tint. Non-sticky (placement is the caller's job).
  tonal,
}

/// Text-only action button (DESIGN §4). No icons. The label **never shrinks and never clips**:
/// it wraps as far as it needs to and the button grows, so a long German label at accessibility
/// text sizes stays fully readable. Honors the 48px minimum interactive target in both axes.
class SgartButton extends StatelessWidget {
  const SgartButton({
    super.key,
    required this.label,
    required this.onPressed,
    this.variant = SgartButtonVariant.primary,
  });

  /// Caller-supplied, already-localized label. The component holds no user-facing copy of its
  /// own (localization arrives in Story 1.3).
  final String label;

  /// A null callback renders the button in its disabled treatment.
  final VoidCallback? onPressed;

  final SgartButtonVariant variant;

  /// How far a disabled button fades. Enough to read as unavailable while keeping the label
  /// legible — DESIGN §5 asks for forgiving, plain screens, not invisible ones.
  static const double disabledOpacity = 0.38;

  bool get _isEnabled => onPressed != null;

  @override
  Widget build(BuildContext context) {
    final colors = context.sgartColors;
    final labelStyle = Theme.of(context).textTheme.labelLarge ?? const TextStyle();

    final (Color background, Color foreground, BorderSide side) = switch (variant) {
      SgartButtonVariant.primary => (colors.primary, colors.onPrimary, BorderSide.none),
      SgartButtonVariant.secondary => (
          Colors.transparent,
          colors.primary,
          BorderSide(color: colors.primary, width: SgartShapes.hairline),
        ),
      SgartButtonVariant.tonal => (
          colors.primary.withValues(alpha: SgartColors.tintAlpha),
          colors.onPrimaryTint,
          BorderSide.none,
        ),
    };

    final resolvedBackground =
        _isEnabled ? background : _fade(background, colors.background);
    final resolvedForeground =
        _isEnabled ? foreground : _fade(foreground, colors.background);
    final resolvedSide = _isEnabled || side == BorderSide.none
        ? side
        : BorderSide(color: resolvedForeground, width: side.width);

    return Semantics(
      button: true,
      enabled: _isEnabled,
      label: label,
      excludeSemantics: true,
      child: ConstrainedBox(
        constraints: const BoxConstraints(
          minHeight: SgartShapes.minTapTarget,
          minWidth: SgartShapes.minTapTarget,
        ),
        child: Material(
          color: resolvedBackground,
          shape: RoundedRectangleBorder(
            borderRadius: SgartShapes.button,
            side: resolvedSide,
          ),
          clipBehavior: Clip.antiAlias,
          child: InkWell(
            onTap: onPressed,
            child: Padding(
              padding: const EdgeInsets.symmetric(
                horizontal: SgartShapes.space4,
                vertical: SgartShapes.space2,
              ),
              child: Center(
                widthFactor: 1,
                child: Text(
                  label,
                  textAlign: TextAlign.center,
                  softWrap: true,
                  // No line cap and no FittedBox: the label neither shrinks nor truncates —
                  // it wraps and the button grows (DESIGN §4, AC2).
                  overflow: TextOverflow.visible,
                  style: labelStyle.copyWith(color: resolvedForeground),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  /// Blends a color toward the page background rather than lowering its alpha, so a disabled
  /// button still composites predictably over whatever sits behind it.
  static Color _fade(Color color, Color background) =>
      Color.alphaBlend(color.withValues(alpha: disabledOpacity), background);
}
