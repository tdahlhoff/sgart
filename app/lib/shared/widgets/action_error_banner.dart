import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter/semantics.dart';

import '../../l10n/gen/app_localizations.dart';
import '../../theme/sgart_theme_access.dart';
import '../../theme/tokens/sgart_colors.dart';
import '../../theme/tokens/sgart_shapes.dart';
import '../errors/app_error.dart';
import '../errors/error_message_resolver.dart';

/// The one place a rejected action reports itself: a tinted, icon-led banner that slides in, shakes
/// once so the eye catches it, and is announced to screen readers. Renders nothing while [error] is
/// `null`. A new [error] replays the entrance (each action clears the previous error first, so two
/// identical rejections in a row still play it twice).
///
/// Never dismisses itself — a message that disappears on a timer cannot be read at the member's own
/// pace. The owner clears it on the next action, or via [onDismiss] (shows the close button).
///
/// Pink is a fill, never a hairline or text colour (DESIGN §1): the left bar is a solid fill, the
/// text uses the contrast-checked [SgartColors.onErrorTint].
class ActionErrorBanner extends StatefulWidget {
  const ActionErrorBanner({super.key, required this.error, this.onDismiss});

  final AppError? error;
  final VoidCallback? onDismiss;

  @override
  State<ActionErrorBanner> createState() => _ActionErrorBannerState();
}

class _ActionErrorBannerState extends State<ActionErrorBanner> with SingleTickerProviderStateMixin {
  static const Duration _entranceAndShake = Duration(milliseconds: 560);

  /// The entrance takes the first 200 of 560 ms; the shake fills the rest.
  static const double _shakeStart = 200 / 560;
  static const double _shakeSwings = 3;
  static const double _shakeDistance = 6;

  late final AnimationController _controller;

  @override
  void initState() {
    super.initState();
    _controller = AnimationController(vsync: this, duration: _entranceAndShake);
    if (widget.error != null) {
      _play();
    }
  }

  @override
  void didUpdateWidget(ActionErrorBanner oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.error != null && widget.error != oldWidget.error) {
      _play();
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _play() {
    // A frame later: the build context needs the inherited media/locale data to announce.
    WidgetsBinding.instance.addPostFrameCallback((_) => _announceAndAnimate());
    if (_controller.isAnimating || _controller.value > 0) {
      _controller.reset();
    }
  }

  void _announceAndAnimate() {
    if (!mounted || widget.error == null) {
      return;
    }
    final message = _message(context);
    SemanticsService.sendAnnouncement(View.of(context), message, Directionality.of(context));
    if (MediaQuery.disableAnimationsOf(context)) {
      _controller.value = 1;
    } else {
      _controller.forward(from: 0);
    }
  }

  String _message(BuildContext context) =>
      localizedMessageForErrorCode(AppLocalizations.of(context), widget.error!.code);

  @override
  Widget build(BuildContext context) {
    final error = widget.error;
    if (error == null) {
      return const SizedBox.shrink();
    }
    final colors = context.sgartColors;
    final message = _message(context);
    final reduceMotion = MediaQuery.disableAnimationsOf(context);

    final banner = Padding(
      padding: const EdgeInsets.fromLTRB(
        SgartShapes.space4,
        SgartShapes.space2,
        SgartShapes.space4,
        SgartShapes.space2,
      ),
      child: Semantics(
        key: const Key('action-error-banner-semantics'),
        container: true,
        liveRegion: true,
        label: message,
        excludeSemantics: true,
        child: ClipRRect(
          borderRadius: SgartShapes.button,
          child: DecoratedBox(
            key: const Key('action-error-banner-surface'),
            decoration: BoxDecoration(color: colors.error.withValues(alpha: SgartColors.tintAlpha)),
            child: IntrinsicHeight(
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  ColoredBox(color: colors.error, child: const SizedBox(width: SgartShapes.spaceUnit)),
                  Padding(
                    padding: const EdgeInsets.fromLTRB(SgartShapes.space3, SgartShapes.space3, 0, SgartShapes.space3),
                    child: Icon(Icons.error_outline, color: colors.onErrorTint),
                  ),
                  Expanded(
                    child: Padding(
                      padding: const EdgeInsets.symmetric(horizontal: SgartShapes.space3, vertical: SgartShapes.space3),
                      child: Text(
                        message,
                        key: const Key('item-list-action-error'),
                        style: Theme.of(context).textTheme.bodyMedium?.copyWith(color: colors.onErrorTint),
                      ),
                    ),
                  ),
                  if (widget.onDismiss != null)
                    IconButton(
                      key: const Key('action-error-banner-dismiss'),
                      icon: Icon(Icons.close, color: colors.onErrorTint),
                      tooltip: MaterialLocalizations.of(context).closeButtonTooltip,
                      onPressed: widget.onDismiss,
                    ),
                ],
              ),
            ),
          ),
        ),
      ),
    );

    if (reduceMotion) {
      return banner;
    }
    return AnimatedBuilder(
      animation: _controller,
      child: banner,
      builder: (context, child) {
        final entrance = Curves.easeOutCubic.transform((_controller.value / _shakeStart).clamp(0.0, 1.0));
        final shakeProgress = ((_controller.value - _shakeStart) / (1 - _shakeStart)).clamp(0.0, 1.0);
        final shakeOffset = math.sin(shakeProgress * _shakeSwings * 2 * math.pi) * _shakeDistance * (1 - shakeProgress);
        return ClipRect(
          child: Align(
            alignment: Alignment.topCenter,
            heightFactor: entrance,
            child: Opacity(
              opacity: entrance,
              child: Transform.translate(offset: Offset(shakeOffset, 0), child: child),
            ),
          ),
        );
      },
    );
  }
}
