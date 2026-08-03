import 'package:flutter/services.dart';

// ─────────────────────────────────────────────────────────────────────────────
// Data model for a discovered AirPlay device
// ─────────────────────────────────────────────────────────────────────────────
class AirPlayDevice {
  final String name;
  final String ip;
  final int port;

  const AirPlayDevice({
    required this.name,
    required this.ip,
    required this.port,
  });

  factory AirPlayDevice.fromMap(Map<dynamic, dynamic> map) {
    return AirPlayDevice(
      name: map['name'] as String,
      ip:   map['ip']   as String,
      port: map['port'] as int,
    );
  }

  @override
  String toString() => 'AirPlayDevice(name: $name, ip: $ip, port: $port)';
}

// ─────────────────────────────────────────────────────────────────────────────
// AirPlayService — thin Dart wrapper around the Kotlin MethodChannel
// ─────────────────────────────────────────────────────────────────────────────
class AirPlayService {
  static const _channel =
      MethodChannel('com.example.airplaybose/airplay');

  // Singleton
  AirPlayService._();
  static final AirPlayService instance = AirPlayService._();

  // ── State ────────────────────────────────────────────────────────────────
  bool _streaming = false;
  bool get isStreaming => _streaming;

  // ── 0. Request MediaProjection ─────────────────────────────────────────────
  /// Prompts the user to allow screen/system audio capture.
  /// This must be granted before calling startStream().
  Future<bool> requestMediaProjection() async {
    try {
      final success = await _channel.invokeMethod<bool>('requestMediaProjection');
      return success ?? false;
    } on PlatformException catch (e) {
      throw AirPlayException('MediaProjection denied', e.code, e.message);
    }
  }

  // ── 1. Discover AirPlay devices ──────────────────────────────────────────
  /// Scans the local network via mDNS (_raop._tcp) for ~5 seconds.
  /// Returns an empty list (never throws) on failure so the UI can handle it.
  Future<List<AirPlayDevice>> discoverDevices() async {
    try {
      final raw = await _channel.invokeMethod<List<dynamic>>('discoverDevices');
      if (raw == null) return [];
      return raw
          .cast<Map<dynamic, dynamic>>()
          .map(AirPlayDevice.fromMap)
          .toList();
    } on PlatformException catch (e) {
      throw AirPlayException('Discovery failed', e.code, e.message);
    }
  }

  // ── 2. Start streaming to a device ───────────────────────────────────────
  /// Performs the full RTSP handshake and begins sending RTP audio.
  Future<void> startStream(AirPlayDevice device) async {
    try {
      await _channel.invokeMethod<String>('startStream', {
        'ip':   device.ip,
        'port': device.port,
      });
      _streaming = true;
    } on PlatformException catch (e) {
      _streaming = false;
      throw AirPlayException('Start stream failed', e.code, e.message);
    }
  }

  // ── 3. Stop the active stream ─────────────────────────────────────────────
  Future<void> stopStream() async {
    try {
      await _channel.invokeMethod<String>('stopStream');
    } on PlatformException catch (e) {
      throw AirPlayException('Stop stream failed', e.code, e.message);
    } finally {
      _streaming = false;
    }
  }

  // ── 4. Set playback volume ────────────────────────────────────────────────
  /// [volume] must be between 0.0 (mute) and 1.0 (maximum).
  Future<void> setVolume(double volume) async {
    assert(volume >= 0.0 && volume <= 1.0, 'Volume must be 0.0–1.0');
    try {
      await _channel.invokeMethod<double>('setVolume', {
        'volume': volume.clamp(0.0, 1.0),
      });
    } on PlatformException catch (e) {
      throw AirPlayException('Set volume failed', e.code, e.message);
    }
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Typed exception for AirPlay errors
// ─────────────────────────────────────────────────────────────────────────────
class AirPlayException implements Exception {
  final String message;
  final String code;
  final String? detail;

  const AirPlayException(this.message, this.code, this.detail);

  @override
  String toString() => 'AirPlayException[$code]: $message${detail != null ? ' — $detail' : ''}';
}
