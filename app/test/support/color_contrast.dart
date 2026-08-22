import 'dart:math' as math;

import 'package:flutter/material.dart';

/// WCAG 2.1 relative-contrast ratio between two opaque colors.
double contrastRatio(Color foreground, Color background) {
  final foregroundLuminance = foreground.computeLuminance();
  final backgroundLuminance = background.computeLuminance();
  final lighter = math.max(foregroundLuminance, backgroundLuminance);
  final darker = math.min(foregroundLuminance, backgroundLuminance);
  return (lighter + 0.05) / (darker + 0.05);
}

/// Composites a translucent tint over an opaque background, the way a [DecoratedBox] does.
Color tintOver(Color tint, double alpha, Color background) =>
    Color.alphaBlend(tint.withValues(alpha: alpha), background);

/// WCAG AA for normal-size text.
const double minimumContrastForNormalText = 4.5;
