package com.example.airplaybose

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.Base64
import android.util.Log
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry
import org.bouncycastle.jce.provider.BouncyCastleProvider
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener
import javax.jmdns.ServiceEvent
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.Security
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.net.ssl.SSLContext

data class AirPlayDevice(
    val name: String,
    val ip: String,
    val port: Int
)

class AirPlayModule : FlutterPlugin, MethodCallHandler, ActivityAware, PluginRegistry.ActivityResultListener {

    companion object {
        private const val TAG = "AirPlayModule"
        private const val CHANNEL = "com.example.airplaybose/airplay"
        private const val SERVICE_TYPE = "_raop._tcp.local."
        private const val REQUEST_MEDIA_PROJECTION = 1001

        private const val AIRPLAY_RSA_PUBLIC_KEY =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA59dE8qLieItsH1WgjrcF" +
            "RKj6eUWqi+bGLOX1HL3U3GhC/j0Qg90u3sG/1CUtwC5vOYvfDmFI6oSFXi5ELabW" +
            "skVpFAjjAab2xeFkVbCBiqBmCyMkEDYnn2HmtFDaLMfgXL9ZoHgl/wkTCmEoYiGb" +
            "wEGGiAmHkizkX3ZQEK1pdKye4BQe2KkKRqHrFZRUAz7zSTVe9HXWQ5OlWFqLnbeK" +
            "EQm4SNMnhDEqoaEMQqEGOBVoriV5XcerxXJ0aq9135M8hl8X5oqHXvB/5vlFmcDt" +
            "N6a+S3eU7bIEBHaEiPFizx9PC4Yf1JKoVJN9un/H80dBJi7SCBIDAQAB"

        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val FRAMES_PER_PACKET = 352
        private const val BYTES_PER_FRAME = 4
        private const val BYTES_PER_PACKET = FRAMES_PER_PACKET * BYTES_PER_FRAME
    }

    private lateinit var channel: MethodChannel
    private var activity: Activity? = null
    private var context: Context? = null
    private var pendingProjectionResult: Result? = null
    
    // Stored projection data
    private var mediaProjectionResultCode: Int = 0
    private var mediaProjectionIntent: Intent? = null
    private var mediaProjection: MediaProjection? = null

    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null

    private fun getWifiInetAddress(ctx: Context): InetAddress? {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager ?: return null
        val connectionInfo = wm.connectionInfo ?: return null
        val ipAddress = connectionInfo.ipAddress
        if (ipAddress == 0) return null
        val ipByteArray = byteArrayOf(
            (ipAddress and 0xff).toByte(),
            (ipAddress shr 8 and 0xff).toByte(),
            (ipAddress shr 16 and 0xff).toByte(),
            (ipAddress shr 24 and 0xff).toByte()
        )
        return try {
            InetAddress.getByAddress(ipByteArray)
        } catch (e: Exception) {
            null
        }
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        Security.addProvider(BouncyCastleProvider())
        channel = MethodChannel(binding.binaryMessenger, CHANNEL)
        channel.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        context = null
        cleanup()
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addActivityResultListener(this)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
        binding.addActivityResultListener(this)
    }

    override fun onDetachedFromActivity() {
        activity = null
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "requestMediaProjection" -> requestMediaProjection(result)
            "discoverDevices" -> discoverDevices(result)
            "startStream"     -> {
                val ip   = call.argument<String>("ip")   ?: return result.error("INVALID_ARG", "ip is required", null)
                val port = call.argument<Int>("port")     ?: return result.error("INVALID_ARG", "port is required", null)
                startStream(ip, port, result)
            }
            "stopStream"      -> stopStream(result)
            "setVolume"       -> {
                val volume = call.argument<Double>("volume") ?: return result.error("INVALID_ARG", "volume is required", null)
                setVolume(volume, result)
            }
            else -> result.notImplemented()
        }
    }

    // =========================================================================
    // MEDIA PROJECTION (System Audio Capture)
    // =========================================================================
    private fun requestMediaProjection(result: Result) {
        if (activity == null) {
            result.error("NO_ACTIVITY", "Activity is not attached", null)
            return
        }
        
        if (mediaProjectionIntent != null && mediaProjectionResultCode == Activity.RESULT_OK) {
            // Already have permission
            result.success(true)
            return
        }

        val mpm = activity!!.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        pendingProjectionResult = result
        activity!!.startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                mediaProjectionResultCode = resultCode
                mediaProjectionIntent = data
                pendingProjectionResult?.success(true)
            } else {
                mediaProjectionIntent = null
                pendingProjectionResult?.error("PROJECTION_DENIED", "User denied screen capture", null)
            }
            pendingProjectionResult = null
            return true
        }
        return false
    }

    // =========================================================================
    // DEVICE DISCOVERY
    // =========================================================================
    private var jmdns: JmDNS? = null
    private val discoveredDevices = mutableListOf<AirPlayDevice>()

    private fun discoverDevices(result: Result) {
        Thread {
            try {
                val wm = context?.applicationContext?.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                multicastLock?.let {
                    if (it.isHeld) it.release()
                }
                multicastLock = wm?.createMulticastLock("AirPlayBoseMulticastLock")?.apply {
                    setReferenceCounted(true)
                    acquire()
                }

                jmdns?.close()
                discoveredDevices.clear()

                val localAddress = context?.let { getWifiInetAddress(it) }
                jmdns = if (localAddress != null) {
                    JmDNS.create(localAddress)
                } else {
                    JmDNS.create()
                }

                jmdns!!.addServiceListener(SERVICE_TYPE, object : ServiceListener {
                    override fun serviceAdded(event: ServiceEvent) {
                        jmdns?.requestServiceInfo(event.type, event.name, 1000)
                    }
                    override fun serviceRemoved(event: ServiceEvent) {
                        val cleanName = if (event.name.contains("@")) event.name.substringAfter("@") else event.name
                        discoveredDevices.removeAll { it.name == cleanName }
                    }
                    override fun serviceResolved(event: ServiceEvent) {
                        val info = event.info
                        val addresses = info.inet4Addresses
                        if (addresses.isNullOrEmpty()) return
                        val ip = addresses[0].hostAddress ?: return
                        val port = info.port
                        val name = info.name
                        val cleanName = if (name.contains("@")) name.substringAfter("@") else name
                        if (discoveredDevices.none { it.ip == ip }) {
                            discoveredDevices.add(AirPlayDevice(cleanName, ip, port))
                        }
                    }
                })

                Thread.sleep(5000)
                val deviceList = discoveredDevices.map { mapOf("name" to it.name, "ip" to it.ip, "port" to it.port) }
                android.os.Handler(android.os.Looper.getMainLooper()).post { result.success(deviceList) }
            } catch (e: Exception) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { result.error("DISCOVERY_FAILED", e.message, null) }
            } finally {
                try {
                    multicastLock?.let {
                        if (it.isHeld) it.release()
                    }
                } catch (_: Exception) {}
                multicastLock = null
            }
        }.start()
    }

    // =========================================================================
    // STREAMING
    // =========================================================================
    private var rtspSocket: Socket? = null
    private var rtspOut: OutputStream? = null
    private var rtspIn: BufferedReader? = null
    private var rtpSocket: DatagramSocket? = null
    private var audioRecord: AudioRecord? = null
    private var streamThread: Thread? = null

    @Volatile private var streaming = false

    private var rtspSessionId: String = ""
    private var rtspCSeq: Int = 0
    private var rtpPort: Int = 6000
    private var controlPort: Int = 6001
    private var timingPort: Int = 6002
    private var serverRtpPort: Int = 0
    private var serverIp: String = ""

    private var aesKey: ByteArray = ByteArray(16)
    private var aesIv: ByteArray = ByteArray(16)
    private var aesKeyEncrypted: String = ""

    private fun startStream(ip: String, port: Int, result: Result) {
        if (streaming) return result.error("ALREADY_STREAMING", "Stop current stream first", null)
        if (mediaProjectionIntent == null || activity == null) {
            return result.error("NO_PROJECTION", "MediaProjection not requested or denied", null)
        }

        Thread {
            try {
                serverIp = ip
                generateAesKey()

                rtspSocket = Socket(ip, port).apply { soTimeout = 5000 }
                rtspOut = rtspSocket!!.getOutputStream()
                rtspIn = BufferedReader(InputStreamReader(rtspSocket!!.getInputStream()))

                sendOptions()
                sendAnnounce(ip)
                sendSetup()
                sendRecord()

                rtpSocket = DatagramSocket(rtpPort)
                val serverAddress = InetAddress.getByName(ip)

                // Start Foreground Service first (required for media projection on Android 10+ and 14+)
                val serviceIntent = Intent(activity ?: context, AirPlayCaptureService::class.java).apply {
                    action = AirPlayCaptureService.ACTION_START
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    (activity ?: context)?.startForegroundService(serviceIntent)
                } else {
                    (activity ?: context)?.startService(serviceIntent)
                }

                // Initialize MediaProjection for AudioPlaybackCapture
                val mpm = activity!!.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mpm.getMediaProjection(mediaProjectionResultCode, mediaProjectionIntent!!)
                
                val audioFormat = AudioFormat.Builder()
                    .setEncoding(AUDIO_FORMAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNEL_CONFIG)
                    .build()

                val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .build()

                val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
                val bufSize = maxOf(minBuf, BYTES_PER_PACKET * 4)
                
                audioRecord = AudioRecord.Builder()
                    .setAudioFormat(audioFormat)
                    .setAudioPlaybackCaptureConfig(playbackConfig)
                    .setBufferSizeInBytes(bufSize)
                    .build()

                audioRecord!!.startRecording()
                streaming = true

                streamThread = Thread {
                    var rtpSeq = 0
                    var rtpTimestamp = 0L
                    val pcmBuffer = ByteArray(BYTES_PER_PACKET)
                    val cipher = buildAesCipher()

                    while (streaming) {
                        val bytesRead = audioRecord!!.read(pcmBuffer, 0, BYTES_PER_PACKET)
                        if (bytesRead < 1) continue

                        val alacFrame = encodeAlac(pcmBuffer, bytesRead)
                        val encrypted = encryptPayload(cipher, alacFrame)
                        val rtpPacket = buildRtpPacket(rtpSeq, rtpTimestamp, encrypted)

                        try {
                            rtpSocket?.send(DatagramPacket(rtpPacket, rtpPacket.size, serverAddress, serverRtpPort))
                        } catch (e: Exception) {}

                        rtpSeq = (rtpSeq + 1) and 0xFFFF
                        rtpTimestamp += FRAMES_PER_PACKET
                    }
                }
                streamThread!!.start()

                android.os.Handler(android.os.Looper.getMainLooper()).post { result.success("streaming") }
            } catch (e: Exception) {
                cleanup()
                android.os.Handler(android.os.Looper.getMainLooper()).post { result.error("STREAM_FAILED", e.message, null) }
            }
        }.start()
    }

    private fun stopStream(result: Result) {
        Thread {
            try {
                if (!streaming) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post { result.success("not_streaming") }
                    return@Thread
                }
                
                streaming = false
                streamThread?.join(2000)
                try { sendTeardown() } catch (e: Exception) {}
                cleanup()
                
                android.os.Handler(android.os.Looper.getMainLooper()).post { result.success("stopped") }
            } catch (e: Exception) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { result.error("STOP_FAILED", e.message, null) }
            }
        }.start()
    }

    private fun setVolume(volume: Double, result: Result) {
        if (!streaming) return result.error("NOT_STREAMING", "No active stream", null)
        Thread {
            try {
                val airplayVolume = if (volume <= 0.0) -144.0 else (volume - 1.0) * 30.0
                sendSetParameter("volume: ${"%.6f".format(airplayVolume)}")
                android.os.Handler(android.os.Looper.getMainLooper()).post { result.success(airplayVolume) }
            } catch (e: Exception) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { result.error("VOLUME_FAILED", e.message, null) }
            }
        }.start()
    }

    // =========================================================================
    // RTSP / CRYPTO / ALAC / RTP HELPERS
    // =========================================================================
    private fun nextCSeq(): Int = ++rtspCSeq

    private fun sendRtsp(method: String, uri: String, extraHeaders: Map<String, String> = emptyMap(), body: String = ""): Map<String, String> {
        val request = buildString {
            append("$method $uri RTSP/1.0\r\nCSeq: ${nextCSeq()}\r\nUser-Agent: AirPlayBose/1.0\r\n")
            if (rtspSessionId.isNotEmpty()) append("Session: $rtspSessionId\r\n")
            extraHeaders.forEach { (k, v) -> append("$k: $v\r\n") }
            if (body.isNotEmpty()) append("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n")
            append("\r\n$body")
        }
        rtspOut!!.write(request.toByteArray(Charsets.UTF_8))
        rtspOut!!.flush()
        return readRtspResponse()
    }

    private fun readRtspResponse(): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        val statusLine = rtspIn!!.readLine() ?: throw Exception("RTSP closed")
        if (!statusLine.contains("200")) throw Exception("RTSP error: $statusLine")
        
        var contentLength = 0
        var line = rtspIn!!.readLine()
        while (!line.isNullOrEmpty()) {
            val idx = line.indexOf(':')
            if (idx > 0) {
                val key = line.substring(0, idx).trim().lowercase()
                val value = line.substring(idx + 1).trim()
                headers[key] = value
                if (key == "content-length") contentLength = value.toIntOrNull() ?: 0
                if (key == "session") rtspSessionId = value.split(';')[0].trim()
            }
            line = rtspIn!!.readLine()
        }
        if (contentLength > 0) rtspIn!!.read(CharArray(contentLength), 0, contentLength)
        return headers
    }

    private fun sendOptions() = sendRtsp("OPTIONS", "rtsp://$serverIp/airplaybose", mapOf("Apple-Challenge" to generateAppleChallenge()))
    private fun sendAnnounce(ip: String) {
        val sdp = "v=0\r\no=AirPlayBose 1 0 IN IP4 127.0.0.1\r\ns=AirPlayBose\r\nc=IN IP4 $ip\r\nt=0 0\r\nm=audio 0 RTP/AVP 96\r\na=rtpmap:96 AppleLossless\r\na=fmtp:96 $FRAMES_PER_PACKET 0 16 40 10 14 2 255 0 0 $SAMPLE_RATE\r\na=rsaaeskey:$aesKeyEncrypted\r\na=aesiv:${Base64.encodeToString(aesIv, Base64.NO_WRAP or Base64.NO_PADDING)}\r\n"
        sendRtsp("ANNOUNCE", "rtsp://$ip/airplaybose", mapOf("Content-Type" to "application/sdp"), sdp)
    }
    private fun sendSetup() {
        val h = sendRtsp("SETUP", "rtsp://$serverIp/airplaybose", mapOf("Transport" to "RTP/AVP/UDP;unicast;interleaved=0-1;mode=record;control_port=$controlPort;timing_port=$timingPort;client_port=$rtpPort"))
        serverRtpPort = Regex("server_port=(\\d+)").find(h["transport"] ?: "")?.groupValues?.get(1)?.toIntOrNull() ?: 6000
    }
    private fun sendRecord() = sendRtsp("RECORD", "rtsp://$serverIp/airplaybose", mapOf("Range" to "npt=0-", "RTP-Info" to "seq=0;rtptime=0"))
    private fun sendTeardown() = sendRtsp("TEARDOWN", "rtsp://$serverIp/airplaybose")
    private fun sendSetParameter(b: String) = sendRtsp("SET_PARAMETER", "rtsp://$serverIp/airplaybose", mapOf("Content-Type" to "text/parameters"), b)

    private fun generateAesKey() {
        aesKey = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey().encoded
        aesIv = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        aesKeyEncrypted = try {
            val cipher = Cipher.getInstance("RSA/None/OAEPWithSHA1AndMGF1Padding", "BC")
            cipher.init(Cipher.ENCRYPT_MODE, KeyFactory.getInstance("RSA", "BC").generatePublic(X509EncodedKeySpec(Base64.decode(AIRPLAY_RSA_PUBLIC_KEY, Base64.DEFAULT))))
            Base64.encodeToString(cipher.doFinal(aesKey), Base64.NO_WRAP or Base64.NO_PADDING)
        } catch (e: Exception) { "" }
    }

    private fun buildAesCipher() = Cipher.getInstance("AES/CBC/NoPadding", "BC").apply { init(Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(aesKey, "AES"), IvParameterSpec(aesIv)) }

    private fun encryptPayload(cipher: Cipher, p: ByteArray): ByteArray {
        val a = p.size - (p.size % 16)
        if (a == 0) return p
        val e = cipher.update(p, 0, a)
        val r = ByteArray(p.size)
        System.arraycopy(e, 0, r, 0, a)
        if (p.size % 16 > 0) System.arraycopy(p, a, r, a, p.size % 16)
        return r
    }

    private fun generateAppleChallenge() = Base64.encodeToString(ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }, Base64.NO_WRAP or Base64.NO_PADDING)

    private fun encodeAlac(pcm: ByteArray, len: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(0x20); out.write(0x00); out.write(0x00)
        var i = 0
        while (i + 1 < len) {
            out.write(pcm[i + 1].toInt() and 0xFF)
            out.write(pcm[i].toInt() and 0xFF)
            i += 2
        }
        return out.toByteArray()
    }

    private fun buildRtpPacket(seq: Int, ts: Long, p: ByteArray): ByteArray {
        val h = ByteArray(12)
        h[0] = 0x80.toByte(); h[1] = (0x80 or 96).toByte()
        h[2] = (seq shr 8).toByte(); h[3] = seq.toByte()
        h[4] = (ts shr 24).toByte(); h[5] = (ts shr 16).toByte(); h[6] = (ts shr 8).toByte(); h[7] = ts.toByte()
        h[11] = 0x01
        return ByteArray(12 + p.size).apply { System.arraycopy(h, 0, this, 0, 12); System.arraycopy(p, 0, this, 12, p.size) }
    }

    private fun cleanup() {
        streaming = false
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { rtpSocket?.close() } catch (_: Exception) {}
        rtpSocket = null
        try { rtspIn?.close(); rtspOut?.close(); rtspSocket?.close() } catch (_: Exception) {}
        rtspIn = null; rtspOut = null; rtspSocket = null
        
        mediaProjection?.stop()
        mediaProjection = null

        // Stop foreground service
        try {
            val serviceIntent = Intent(activity ?: context, AirPlayCaptureService::class.java).apply {
                action = AirPlayCaptureService.ACTION_STOP
            }
            (activity ?: context)?.startService(serviceIntent)
        } catch (_: Exception) {}

        // Clean up discovery components
        try { jmdns?.close() } catch (_: Exception) {}
        jmdns = null
        try {
            multicastLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
        } catch (_: Exception) {}
        multicastLock = null
    }
}
