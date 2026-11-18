package com.ajnetwork.aj_setup

import android.os.Handler
import android.os.Looper
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File

/** Flutter host + "aj/update" (in-app updates) + "aj/adb" (panel tools over the network, see Adb.java). */
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
        val main = Handler(Looper.getMainLooper())
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "aj/adb").setMethodCallHandler { call, result ->
            val ip = call.argument<String>("ip") ?: ""
            val keys = File(filesDir, "adb")
            Thread {
                try {
                    val out: Any = Adb(ip, 5555, keys, 60_000).use { adb ->
                        when (call.method) {
                            "connect" -> adb.banner
                            "shell" -> adb.shell(call.argument<String>("cmd") ?: "")
                            "install" -> adb.install(File(call.argument<String>("path") ?: "").readBytes(), call.argument<String>("flags") ?: "-r -g")
                            else -> throw IllegalArgumentException("unknown " + call.method)
                        }
                    }
                    main.post { result.success(out) }
                } catch (e: Exception) {
                    main.post { result.error("adb", e.message ?: e.toString(), null) }
                }
            }.start()
        }
    }
}
