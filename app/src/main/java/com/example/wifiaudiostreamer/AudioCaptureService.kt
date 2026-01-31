package com.example.wifiaudiostreamer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

@RequiresApi(Build.VERSION_CODES.Q)
class AudioCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var serverThread: Thread? = null
    private var audioManager: AudioManager? = null
    private var serverSocket: ServerSocket? = null
    private var wasMuted: Boolean = false

    @Volatile
    private var isStreaming = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val data: Intent? = intent.getParcelableExtra(EXTRA_DATA)

        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(SERVICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(SERVICE_ID, notification)
        }

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        wasMuted = audioManager!!.isStreamMute(AudioManager.STREAM_MUSIC)
        if (!wasMuted) {
            audioManager!!.setStreamMute(AudioManager.STREAM_MUSIC, true)
        }

        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)

        startStreaming()

        return START_STICKY
    }

    private fun startStreaming() {
        if (isStreaming) return
        isStreaming = true

        serverThread = Thread {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(8080))
                }

                val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .build()

                val bufferSize = AudioRecord.getMinBufferSize(44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)

                audioRecord = AudioRecord.Builder()
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(44100)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setAudioPlaybackCaptureConfig(config)
                    .build()

                audioRecord?.startRecording()

                while (isStreaming) {
                    var clientSocket: Socket? = null
                    try {
                        clientSocket = serverSocket?.accept()
                        val outputStream = clientSocket?.getOutputStream()
                        val buffer = ByteArray(bufferSize)

                        while (isStreaming && clientSocket?.isConnected == true) {
                            val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                            if (read <= 0) break
                            try {
                                outputStream?.write(buffer, 0, read)
                            } catch (e: IOException) {
                                break
                            }
                        }
                    } catch (e: Exception) {
                        // This is expected when the server stops or a client disconnects.
                    } finally {
                        clientSocket?.close()
                    }
                }
            } catch (e: Exception) {
                // This is expected when the server is stopped forcefully.
            } finally {
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
            }
        }
        serverThread?.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        isStreaming = false

        try {
            serverSocket?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }

        audioRecord?.stop()

        try {
            serverThread?.join(500) // Wait for thread to finish
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }

        serverThread = null
        serverSocket = null
        audioRecord = null

        mediaProjection?.stop()
        mediaProjection = null

        if (audioManager != null && !wasMuted) {
            audioManager?.setStreamMute(AudioManager.STREAM_MUSIC, false)
        }
        audioManager = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Audio Streaming", NotificationManager.IMPORTANCE_DEFAULT)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Wifi Audio Streamer")
            .setContentText("Streaming audio in the background.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        private const val SERVICE_ID = 123
        private const val CHANNEL_ID = "AudioCapture"
    }
}
