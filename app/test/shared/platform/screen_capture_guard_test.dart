import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sgart/shared/platform/screen_capture_guard.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('ScreenCaptureGuard', () {
    final nativeCalls = <String>[];

    setUp(() {
      nativeCalls.clear();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        ScreenCaptureGuard.channel,
        (call) async {
          nativeCalls.add(call.method);
          return null;
        },
      );
    });

    tearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(ScreenCaptureGuard.channel, null);
    });

    test('protectAndRelease_forwardTheirCallsToTheNativeSide', () async {
      await ScreenCaptureGuard.protect();
      await ScreenCaptureGuard.release();

      expect(nativeCalls, ['protect', 'release']);
    });

    test('protect_isANoOpWhenNoNativeHandlerIsRegistered', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(ScreenCaptureGuard.channel, null);

      await expectLater(ScreenCaptureGuard.protect(), completes);
    });

    test('protect_swallowsAPlatformExceptionFromTheNativeSide', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        ScreenCaptureGuard.channel,
        (call) async => throw PlatformException(code: 'detached'),
      );

      await expectLater(ScreenCaptureGuard.protect(), completes);
    });
  });
}
