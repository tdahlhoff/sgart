import 'dart:io';
import 'dart:ui' as ui;

import 'package:flutter/rendering.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';

/// Renders [widget] at a phone-realistic size and writes a PNG screenshot to [outputPath] — for
/// visually inspecting a screen's layout without a running emulator, backend, or Keycloak. Not a
/// pass/fail test on its own; pair with a throwaway test file that calls this for whichever
/// screen is under design review, and delete that file once done (this helper is the only
/// permanent piece).
///
/// [surfaceSize]/[devicePixelRatio] default to the Pixel 7 AVD's logical size (matches the
/// screenshots taken from the real emulator during manual test passes).
Future<void> pumpAndScreenshot(
  WidgetTester tester,
  Widget widget,
  String outputPath, {
  Size surfaceSize = const Size(1080, 2400),
  double devicePixelRatio = 2.625,
}) async {
  tester.view.physicalSize = surfaceSize;
  tester.view.devicePixelRatio = devicePixelRatio;
  addTearDown(tester.view.resetPhysicalSize);
  addTearDown(tester.view.resetDevicePixelRatio);

  final boundaryKey = GlobalKey();
  await tester.pumpWidget(RepaintBoundary(key: boundaryKey, child: widget));
  // A fixed number of frames rather than pumpAndSettle(): a screenshot only needs the widget
  // tree built and laid out once, and pumpAndSettle() hangs (its 10-minute cap) against any
  // widget with a never-ending animation ticker (e.g. a focused text cursor's blink), which a
  // static layout snapshot has no reason to wait out.
  for (var i = 0; i < 3; i++) {
    await tester.pump(const Duration(milliseconds: 100));
  }

  final boundary = boundaryKey.currentContext!.findRenderObject()! as RenderRepaintBoundary;
  final image = await boundary.toImage(pixelRatio: 1);
  try {
    final byteData = await image.toByteData(format: ui.ImageByteFormat.png);
    final file = File(outputPath);
    file.parent.createSync(recursive: true);
    file.writeAsBytesSync(byteData!.buffer.asUint8List());
  } finally {
    // An undisposed ui.Image hangs flutter_test's teardown, which asserts every Image it
    // handed out was disposed before the test finishes.
    image.dispose();
  }
}
