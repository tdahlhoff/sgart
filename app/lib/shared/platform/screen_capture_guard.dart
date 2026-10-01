import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// Blocks screenshots, screen recording, and the app-switcher preview while a screen that shows an
/// account secret is open (CLAUDE.md §5, security by default).
///
/// Implemented only on Android (`FLAG_SECURE`, see `MainActivity`). Other platforms have no plugin
/// behind the channel, so the guard silently does nothing there — a missing guard must never break
/// the screen it protects.
class ScreenCaptureGuard {
  const ScreenCaptureGuard._();

  static const MethodChannel channel = MethodChannel('de.sgart.sgart/screen_capture_guard');

  /// Starts blocking capture; pair every call with [release].
  static Future<void> protect() => _invoke('protect');

  /// Stops blocking capture so every other screen stays screenshot-able.
  static Future<void> release() => _invoke('release');

  static Future<void> _invoke(String method) async {
    try {
      await channel.invokeMethod<void>(method);
    } on MissingPluginException {
      // No native handler on this platform or in a widget test — nothing to guard.
    } on PlatformException catch (error) {
      // The guard is a safety net, not a gate: report it, but never break the screen it protects.
      debugPrint('ScreenCaptureGuard.$method failed: $error');
    }
  }
}
