package de.sgart.sgart

import android.view.WindowManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        // Only the screens that show an account secret opt in (see ScreenCaptureGuard), so every
        // other screen stays screenshot-able.
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, SCREEN_CAPTURE_GUARD_CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "protect" -> window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    "release" -> window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    else -> {
                        result.notImplemented()
                        return@setMethodCallHandler
                    }
                }
                result.success(null)
            }
    }

    private companion object {
        const val SCREEN_CAPTURE_GUARD_CHANNEL = "de.sgart.sgart/screen_capture_guard"
    }
}
