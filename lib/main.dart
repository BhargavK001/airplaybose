import 'package:flutter/material.dart';
import 'package:audio_session/audio_session.dart';
import 'package:permission_handler/permission_handler.dart';
import 'services/airplay_service.dart';

void main() {
  runApp(const AirPlayBoseApp());
}

class AirPlayBoseApp extends StatelessWidget {
  const AirPlayBoseApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'AirPlay Bose',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF00B4D8),
          brightness: Brightness.dark,
        ),
        useMaterial3: true,
      ),
      home: const HomeScreen(),
    );
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// HomeScreen — device discovery + stream controls
// ─────────────────────────────────────────────────────────────────────────────
class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  final _service = AirPlayService.instance;

  // UI state
  bool _discovering = false;
  bool _streaming   = false;
  double _volume    = 0.7;
  String _statusMsg = 'Ready';

  List<AirPlayDevice> _devices     = [];
  AirPlayDevice?      _activeDevice;

  @override
  void initState() {
    super.initState();
    _initAudioSession();
    _requestPermissions();
  }

  Future<void> _initAudioSession() async {
    final session = await AudioSession.instance;
    await session.configure(const AudioSessionConfiguration.music());
    await session.setActive(true);
  }

  Future<void> _requestPermissions() async {
    try {
      final status = await Permission.microphone.request();
      if (status != PermissionStatus.granted) {
        _showError('Microphone/Record Audio permission is required to capture system audio.');
        return;
      }
      await _service.requestMediaProjection();
    } on AirPlayException catch (e) {
      _showError(e.toString());
    }
  }

  // ── Discovery ──────────────────────────────────────────────────────────────
  Future<void> _discover() async {
    setState(() {
      _discovering = true;
      _devices     = [];
      _statusMsg   = 'Scanning for AirPlay devices…';
    });

    try {
      final found = await _service.discoverDevices();
      setState(() {
        _devices   = found;
        _statusMsg = found.isEmpty
            ? 'No devices found. Make sure you\'re on the same Wi-Fi network.'
            : '${found.length} device(s) found';
      });
    } on AirPlayException catch (e) {
      _showError(e.toString());
      setState(() => _statusMsg = 'Discovery failed');
    } finally {
      setState(() => _discovering = false);
    }
  }

  // ── Start / Stop stream ────────────────────────────────────────────────────
  Future<void> _toggleStream(AirPlayDevice device) async {
    if (_streaming) {
      // Stop
      setState(() => _statusMsg = 'Stopping stream…');
      try {
        await _service.stopStream();
        setState(() {
          _streaming    = false;
          _activeDevice = null;
          _statusMsg    = 'Stopped';
        });
      } on AirPlayException catch (e) {
        _showError(e.toString());
      }
    } else {
      // Start
      setState(() => _statusMsg = 'Connecting to ${device.name}…');
      try {
        await _service.startStream(device);
        // Apply initial volume after connecting
        await _service.setVolume(_volume);
        setState(() {
          _streaming    = true;
          _activeDevice = device;
          _statusMsg    = 'Streaming to ${device.name}';
        });
      } on AirPlayException catch (e) {
        _showError(e.toString());
        setState(() => _statusMsg = 'Connection failed');
      }
    }
  }

  // ── Volume ─────────────────────────────────────────────────────────────────
  Future<void> _onVolumeChanged(double value) async {
    setState(() => _volume = value);
    if (_streaming) {
      try {
        await _service.setVolume(value);
      } on AirPlayException catch (e) {
        _showError(e.toString());
      }
    }
  }

  // ── Error snackbar ─────────────────────────────────────────────────────────
  void _showError(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(message),
        backgroundColor: Colors.red.shade700,
        behavior: SnackBarBehavior.floating,
      ),
    );
  }

  // ── Build ──────────────────────────────────────────────────────────────────
  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;

    return Scaffold(
      backgroundColor: const Color(0xFF0A0E1A),
      appBar: AppBar(
        backgroundColor: const Color(0xFF0A0E1A),
        title: Row(
          children: [
            Icon(Icons.cast, color: cs.primary, size: 28),
            const SizedBox(width: 10),
            const Text(
              'AirPlay Bose',
              style: TextStyle(
                fontWeight: FontWeight.bold,
                color: Colors.white,
                letterSpacing: 0.5,
              ),
            ),
          ],
        ),
        actions: [
          // Scan button
          IconButton(
            tooltip: 'Scan for devices',
            icon: _discovering
                ? SizedBox(
                    width: 20,
                    height: 20,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      color: cs.primary,
                    ),
                  )
                : Icon(Icons.wifi_find, color: cs.primary),
            onPressed: _discovering ? null : _discover,
          ),
        ],
      ),

      body: Column(
        children: [
          // ── Status banner ───────────────────────────────────────────────
          _StatusBanner(
            message: _statusMsg,
            streaming: _streaming,
          ),

          // ── Volume bar (shown when streaming) ───────────────────────────
          if (_streaming) _VolumeControl(
            volume: _volume,
            onChanged: _onVolumeChanged,
            activeColor: cs.primary,
          ),

          // ── Device list ─────────────────────────────────────────────────
          Expanded(
            child: _devices.isEmpty
                ? _EmptyState(onScan: _discover, discovering: _discovering)
                : ListView.builder(
                    padding: const EdgeInsets.all(16),
                    itemCount: _devices.length,
                    itemBuilder: (ctx, i) {
                      final device    = _devices[i];
                      final isActive  = _activeDevice?.ip == device.ip;
                      return _DeviceTile(
                        device:   device,
                        isActive: isActive,
                        streaming: _streaming,
                        onTap: () => _toggleStream(device),
                      );
                    },
                  ),
          ),
        ],
      ),
    );
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Status Banner
// ─────────────────────────────────────────────────────────────────────────────
class _StatusBanner extends StatelessWidget {
  final String message;
  final bool streaming;

  const _StatusBanner({required this.message, required this.streaming});

  @override
  Widget build(BuildContext context) {
    return AnimatedContainer(
      duration: const Duration(milliseconds: 300),
      width: double.infinity,
      padding: const EdgeInsets.symmetric(vertical: 10, horizontal: 20),
      color: streaming
          ? const Color(0xFF00B4D8).withValues(alpha: 0.15)
          : Colors.white.withValues(alpha: 0.04),
      child: Row(
        children: [
          Icon(
            streaming ? Icons.graphic_eq : Icons.info_outline,
            size: 18,
            color: streaming ? const Color(0xFF00B4D8) : Colors.white54,
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              message,
              style: TextStyle(
                color: streaming ? const Color(0xFF00B4D8) : Colors.white54,
                fontSize: 13,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Volume Control
// ─────────────────────────────────────────────────────────────────────────────
class _VolumeControl extends StatelessWidget {
  final double volume;
  final ValueChanged<double> onChanged;
  final Color activeColor;

  const _VolumeControl({
    required this.volume,
    required this.onChanged,
    required this.activeColor,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
      child: Row(
        children: [
          const Icon(Icons.volume_down, color: Colors.white54, size: 20),
          Expanded(
            child: Slider(
              value: volume,
              onChanged: onChanged,
              activeColor: activeColor,
              inactiveColor: Colors.white12,
            ),
          ),
          const Icon(Icons.volume_up, color: Colors.white54, size: 20),
        ],
      ),
    );
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Device Tile
// ─────────────────────────────────────────────────────────────────────────────
class _DeviceTile extends StatelessWidget {
  final AirPlayDevice device;
  final bool isActive;
  final bool streaming;
  final VoidCallback onTap;

  const _DeviceTile({
    required this.device,
    required this.isActive,
    required this.streaming,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;

    return Card(
      color: isActive
          ? cs.primary.withValues(alpha: 0.15)
          : Colors.white.withValues(alpha: 0.06),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(14),
        side: BorderSide(
          color: isActive ? cs.primary : Colors.transparent,
          width: 1.5,
        ),
      ),
      margin: const EdgeInsets.only(bottom: 12),
      child: ListTile(
        contentPadding: const EdgeInsets.symmetric(horizontal: 18, vertical: 10),
        leading: CircleAvatar(
          backgroundColor: isActive
              ? cs.primary.withValues(alpha: 0.25)
              : Colors.white10,
          child: Icon(
            Icons.speaker,
            color: isActive ? cs.primary : Colors.white54,
          ),
        ),
        title: Text(
          device.name,
          style: TextStyle(
            color: Colors.white,
            fontWeight: isActive ? FontWeight.bold : FontWeight.normal,
          ),
        ),
        subtitle: Text(
          '${device.ip}:${device.port}',
          style: const TextStyle(color: Colors.white38, fontSize: 12),
        ),
        trailing: ElevatedButton.icon(
          onPressed: (streaming && !isActive) ? null : onTap,
          style: ElevatedButton.styleFrom(
            backgroundColor: isActive ? Colors.red.shade700 : cs.primary,
            foregroundColor: Colors.white,
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(10),
            ),
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 8),
          ),
          icon: Icon(
            isActive ? Icons.stop : Icons.cast,
            size: 18,
          ),
          label: Text(isActive ? 'Stop' : 'Stream'),
        ),
      ),
    );
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Empty state — no devices found yet
// ─────────────────────────────────────────────────────────────────────────────
class _EmptyState extends StatelessWidget {
  final VoidCallback onScan;
  final bool discovering;

  const _EmptyState({required this.onScan, required this.discovering});

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(40.0),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(
              Icons.speaker_group_outlined,
              size: 72,
              color: Colors.white12,
            ),
            const SizedBox(height: 20),
            const Text(
              'No devices found',
              style: TextStyle(
                color: Colors.white54,
                fontSize: 18,
                fontWeight: FontWeight.w500,
              ),
            ),
            const SizedBox(height: 8),
            const Text(
              'Make sure your Bose speaker is on\nand connected to the same Wi-Fi network.',
              textAlign: TextAlign.center,
              style: TextStyle(color: Colors.white30, fontSize: 13),
            ),
            const SizedBox(height: 28),
            ElevatedButton.icon(
              onPressed: discovering ? null : onScan,
              icon: discovering
                  ? const SizedBox(
                      width: 16,
                      height: 16,
                      child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                    )
                  : const Icon(Icons.wifi_find),
              label: Text(discovering ? 'Scanning…' : 'Scan for Devices'),
              style: ElevatedButton.styleFrom(
                backgroundColor: const Color(0xFF00B4D8),
                foregroundColor: Colors.white,
                padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 14),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(12),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
