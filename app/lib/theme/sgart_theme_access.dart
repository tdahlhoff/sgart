import 'package:flutter/material.dart';

import 'tokens/sgart_colors.dart';

/// Reads the SGART semantic color tokens off the ambient theme.
///
/// The tokens travel as a [ThemeExtension], which is optional by construction, so a subtree
/// themed by anything other than `SgartTheme` would otherwise fail with a bare
/// "Null check operator used on a null value". Fail Fast means failing *diagnosably*: this
/// says which wiring is missing.
extension SgartThemeAccess on BuildContext {
  SgartColors get sgartColors {
    final colors = Theme.of(this).extension<SgartColors>();
    assert(
      colors != null,
      'No SgartColors found on the ambient Theme. SGART widgets must be rendered under a '
      'ThemeData built by SgartTheme.light() or SgartTheme.dark() — a bare ThemeData, or a '
      'Theme/dialog override that drops ThemeData.extensions, will not carry the design '
      'tokens.',
    );
    return colors ?? SgartColors.light();
  }
}
