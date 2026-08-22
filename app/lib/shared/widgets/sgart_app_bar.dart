import 'package:flutter/material.dart';

/// Screen chrome that follows the theme (DESIGN §1) and survives OS Dynamic Type.
///
/// A stock [AppBar] keeps a fixed toolbar height while its title scales without limit, so at
/// iOS accessibility sizes the title clips. Chrome is the one place where clamping is the right
/// answer — the title is a landmark, not content, and the content below it still scales freely.
class SgartAppBar extends StatelessWidget implements PreferredSizeWidget {
  const SgartAppBar({super.key, required this.title, this.actions});

  /// Caller-supplied, already-localized title.
  final String title;

  final List<Widget>? actions;

  /// The default 56px toolbar fits the 21px title at up to 1.5× (≈39px of line box).
  static const double maximumTitleTextScale = 1.5;

  @override
  Size get preferredSize => const Size.fromHeight(kToolbarHeight);

  @override
  Widget build(BuildContext context) {
    return AppBar(
      title: MediaQuery.withClampedTextScaling(
        maxScaleFactor: maximumTitleTextScale,
        child: Text(title),
      ),
      actions: actions,
    );
  }
}
