package com.example.airplaybose

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity : FlutterActivity() {

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // Register AirPlayModule so Flutter can invoke its MethodChannel
        flutterEngine.plugins.add(AirPlayModule())
    }
}
