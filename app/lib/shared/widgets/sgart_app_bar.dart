import 'package:flutter/material.dart';

/// Screen chrome that follows the theme (DESIGN §1) and survives OS Dynamic Type.
///
/// A stock [AppBar] keeps a fixed toolbar height while its title scales without limit, so at
/// iOS accessibility sizes the title clips. Chrome is the one place where clamping is the right
/// answer — the title is a landmark, not content, and the content below it still scales freely.
class SgartAppBar extends StatelessWidget implements PreferredSizeWidget {
  const SgartAppBar({
    super.key,
    required this.title,
    this.actions,
    this.onTitleTap,
    this.titleKey,
    this.onTitleTapSemanticLabel,
  });

  /// Caller-supplied, already-localized title.
  final String title;

  final List<Widget>? actions;

  /// When set, the title becomes a tappable "switcher chip" (Story 1.7): the name plus a
  /// drop-down affordance, wrapped in an [InkWell] that invokes this callback. Left unset, the
  /// title stays a plain landmark (the pre-1.7 behavior).
  final VoidCallback? onTitleTap;

  /// Key for the tappable title chip, so tests can find it. Ignored when [onTitleTap] is null.
  final Key? titleKey;

  /// Already-localized description of what tapping the chip does (e.g. "switch household"), used as
  /// its tooltip and screen-reader label so the chip announces itself as an actionable control
  /// rather than a bare title. Ignored when [onTitleTap] is null.
  final String? onTitleTapSemanticLabel;

  /// The default 56px toolbar fits the 21px title at up to 1.5× (≈39px of line box).
  static const double maximumTitleTextScale = 1.5;

  @override
  Size get preferredSize => const Size.fromHeight(kToolbarHeight);

  @override
  Widget build(BuildContext context) {
    final clampedTitle = MediaQuery.withClampedTextScaling(
      maxScaleFactor: maximumTitleTextScale,
      child: Text(title),
    );

    return AppBar(
      title: onTitleTap == null ? clampedTitle : _switcherChip(clampedTitle),
      actions: actions,
    );
  }

  Widget _switcherChip(Widget clampedTitle) {
    final chip = InkWell(
      key: titleKey,
      onTap: onTitleTap,
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Flexible(child: clampedTitle),
          const Icon(Icons.arrow_drop_down),
        ],
      ),
    );
    final label = onTitleTapSemanticLabel;
    if (label == null) {
      return chip;
    }
    return Tooltip(
      message: label,
      child: Semantics(button: true, label: label, child: chip),
    );
  }
}
