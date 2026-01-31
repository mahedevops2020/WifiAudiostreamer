package com.example.wifiaudiostreamer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

class AudioClient {

    @Volatile
    private var isPlaying = false
    private var audioTrack: AudioTrack? = null
    private var clientSocket: Socket? = null

    fun startPlaying(serverIp: String, onConnected: () -> Unit, onError: (String) -> Unit, onDisconnected: () -> Unit) {
        if (isPlaying) return

        Thread {
            try {
                val socket = Socket()
                // Connect with a 5-second timeout
                socket.connect(InetSocketAddress(serverIp, 8080), 5000)
                clientSocket = socket

                // Connection successful, notify UI
                isPlaying = true
                onConnected()

                val bufferSize = AudioTrack.getMinBufferSize(44100, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(44100).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(bufferSize).build()

                val inputStream = socket.getInputStream()
                val buffer = ByteArray(bufferSize)

                audioTrack?.play()

                while (isPlaying) {
                    val read = inputStream.read(buffer, 0, buffer.size)
                    if (read == -1) break // Server disconnected
                    audioTrack?.write(buffer, 0, read)
                }

            } catch (e: SocketTimeoutException) {
                onError("Connection timed out. Check IP and network.")
            } catch (e: Exception) {
                e.printStackTrace()
                onError("Connection failed. Check IP and network.")
            } finally {
                val wasPlaying = isPlaying
                stopPlaying()
                if (wasPlaying) {
                    onDisconnected()
                }
            }
        }.start()
    }

    fun stopPlaying() {
        isPlaying = false
        try { audioTrack?.stop() } catch (e: Exception) { /* ignore */ }
        try { audioTrack?.release() } catch (e: Exception) { /* ignore */ }
        audioTrack = null
        try { clientSocket?.close() } catch (e: Exception) { /* ignore */ }
        clientSocket = null
    }
}
