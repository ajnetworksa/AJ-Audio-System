package com.ajnetwork.aj_audio

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/** Flutter host + "aj/capture" (Play my phone) + "aj/update" (in-app updates). */
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
                "configure" -> {
                    Capture.configure(this, call.argument<String>("server") ?: "", call.argument<String>("token") ?: "",
                        call.argument<Boolean>("mute") ?: true)
                    result.success(true)
                }
                "stop" -> { Capture.stop(this); result.success(true) }
                "status" -> result.success(Capture.status(this))
                "songInfo" -> { MediaWatch.openSettings(this); result.success(true) }
                else -> result.notImplemented()
            }
        }
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
