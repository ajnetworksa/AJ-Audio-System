package com.ajnetwork.aj_audio

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/** Flutter host + the "aj/capture" channel for "Play my phone" (Android only). */
class MainActivity : FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "aj/capture").setMethodCallHandler { call, result ->
            when (call.method) {
                "start" -> {
                    Capture.saveTarget(
                        this,
                        call.argument<String>("server") ?: "",
                        call.argument<String>("token") ?: "",
                        call.argument<String>("target") ?: "",
                        call.argument<String>("targetName") ?: ""
                    )
                    Capture.start(this)
                    result.success(true)
                }
                "stop" -> { Capture.stop(this); result.success(true) }
                "status" -> result.success(Capture.status(this))
                else -> result.notImplemented()
            }
        }
    }
}
