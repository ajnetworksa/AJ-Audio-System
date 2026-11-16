package com.ajnetwork.aj_setup

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/** Flutter host + the "aj/update" channel (in-app updates from GitHub Releases). */
class MainActivity : FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "aj/update").setMethodCallHandler { call, result ->
            when (call.method) {
                "version" -> result.success(AppUpdater.version(this))
                "install" -> { AppUpdater.start(this, call.argument<String>("url") ?: ""); result.success(true) }
                "state" -> result.success(AppUpdater.state)
                else -> result.notImplemented()
            }
        }
    }
}
