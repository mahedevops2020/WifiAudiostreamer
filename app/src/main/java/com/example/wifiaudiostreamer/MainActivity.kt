package com.example.wifiaudiostreamer

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.wifiaudiostreamer.ui.theme.WifiAudioStreamerTheme
import java.net.NetworkInterface

@RequiresApi(Build.VERSION_CODES.Q)
class MainActivity : ComponentActivity() {

    private val audioClient by lazy { AudioClient() }
    private val preferenceManager by lazy { PreferenceManager(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WifiAudioStreamerTheme {
                AppNavigation(audioClient = audioClient, preferenceManager = preferenceManager)
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
@Composable
fun AppNavigation(audioClient: AudioClient, preferenceManager: PreferenceManager) {
    var currentScreen by remember { mutableStateOf("selection") }

    Scaffold(modifier = Modifier.fillMaxSize()) {
        innerPadding ->
        when (currentScreen) {
            "selection" -> ModeSelectionScreen(
                modifier = Modifier.padding(innerPadding),
                onServerClicked = { currentScreen = "server" },
                onClientClicked = { currentScreen = "client" }
            )
            "server" -> ServerScreen(modifier = Modifier.padding(innerPadding))
            "client" -> ClientScreen(
                modifier = Modifier.padding(innerPadding),
                audioClient = audioClient,
                preferenceManager = preferenceManager
            )
        }
    }
}

@Composable
fun ModeSelectionScreen(modifier: Modifier = Modifier, onServerClicked: () -> Unit, onClientClicked: () -> Unit) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "Choose App Mode", modifier = Modifier.padding(bottom = 24.dp))
        Button(onClick = onServerClicked) {
            Text("Act as Server (TV Mode)")
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onClientClicked) {
            Text("Act as Client (Phone Mode)")
        }
    }
}


@RequiresApi(Build.VERSION_CODES.Q)
@Composable
fun ServerScreen(modifier: Modifier = Modifier) {
    var isStreaming by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Disconnected. Press Start to request permission.") }
    val context = LocalContext.current
    val ipAddress = getIpAddress()

    val mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            status = "Permission granted! Starting stream..."
            val serviceIntent = Intent(context, AudioCaptureService::class.java).apply {
                putExtra(AudioCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(AudioCaptureService.EXTRA_DATA, result.data)
            }
            context.startForegroundService(serviceIntent)
            isStreaming = true
            status = "Streaming..."
        } else {
            status = "Permission denied. Cannot stream audio."
        }
    }

    val requestAudioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        } else {
            status = "RECORD_AUDIO permission is required to capture audio."
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "Wifi Audio Streamer (Server)", modifier = Modifier.padding(bottom = 16.dp))
        Text(text = "IP Address: $ipAddress", modifier = Modifier.padding(bottom = 16.dp))
        Text(text = "Status: $status", modifier = Modifier.padding(bottom = 16.dp))
        Button(onClick = {
            if (isStreaming) {
                context.stopService(Intent(context, AudioCaptureService::class.java))
                isStreaming = false
                status = "Disconnected"
            } else {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    mediaProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                } else {
                    requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }) {
            Text(text = if (isStreaming) "Stop Streaming" else "Start Streaming")
        }
    }
}

private fun getIpAddress(): String {
    try {
        val networkInterfaces = NetworkInterface.getNetworkInterfaces()
        while (networkInterfaces.hasMoreElements()) {
            val networkInterface = networkInterfaces.nextElement()
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (!address.isLoopbackAddress && address.hostAddress.indexOf(':') < 0) {
                    return address.hostAddress
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return "0.0.0.0"
}

@Composable
fun ClientScreen(
    modifier: Modifier = Modifier,
    audioClient: AudioClient,
    preferenceManager: PreferenceManager
) {
    var isPlaying by remember { mutableStateOf(false) }
    var isConnecting by remember { mutableStateOf(false) }
    val recentIps = remember { preferenceManager.getRecentIpAddresses() }
    var serverIp by remember { mutableStateOf(recentIps.firstOrNull() ?: "") }
    var status by remember { mutableStateOf("Disconnected") }
    var expanded by remember { mutableStateOf(false) }


    DisposableEffect(Unit) {
        onDispose {
            audioClient.stopPlaying()
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "Wifi Audio Streamer (Client)", modifier = Modifier.padding(bottom = 16.dp))
        Box {
            TextField(
                value = serverIp,
                onValueChange = { serverIp = it },
                label = { Text("Enter Server IP") },
                enabled = !isPlaying && !isConnecting,
                modifier = Modifier.padding(bottom = 16.dp),
                trailingIcon = {
                    if (recentIps.isNotEmpty()) {
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = "Show recent IPs",
                            Modifier.clickable { expanded = !expanded }
                        )
                    }
                }
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                recentIps.forEach { ip ->
                    DropdownMenuItem(
                        text = { Text(ip) },
                        onClick = {
                            serverIp = ip
                            expanded = false
                        }
                    )
                }
            }
        }
        Text(text = "Status: $status", modifier = Modifier.padding(bottom = 16.dp))
        Button(
            onClick = {
                if (isPlaying) {
                    audioClient.stopPlaying()
                    // Manually reset the UI state
                    isPlaying = false
                    isConnecting = false
                    status = "Disconnected"
                } else {
                    if (serverIp.isNotBlank()) {
                        isConnecting = true
                        status = "Connecting..."
                        preferenceManager.saveIpAddress(serverIp)
                        audioClient.startPlaying(
                            serverIp = serverIp,
                            onConnected = {
                                isConnecting = false
                                isPlaying = true
                                status = "Connected to $serverIp"
                            },
                            onError = { errorMessage ->
                                isConnecting = false
                                isPlaying = false
                                status = errorMessage
                            },
                            onDisconnected = {
                                isConnecting = false
                                isPlaying = false
                                status = "Disconnected"
                            }
                        )
                    } else {
                        status = "Please enter a server IP"
                    }
                }
            },
            enabled = !isConnecting
        ) {
            Text(text = if (isPlaying) "Stop Playing" else "Start Playing")
        }
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
@Preview(showBackground = true, device = "spec:shape=Normal,width=360,height=640,unit=dp,dpi=480")
@Composable
fun ClientScreenPreview() {
    WifiAudioStreamerTheme {
        val audioClient = AudioClient()
        val context = LocalContext.current
        val preferenceManager = PreferenceManager(context)
        ClientScreen(audioClient = audioClient, preferenceManager = preferenceManager)
    }
}

@RequiresApi(Build.VERSION_CODES.Q)
@Preview(showBackground = true, device = "spec:shape=Normal,width=1920,height=1080,unit=dp,dpi=480")
@Composable
fun ServerScreenPreview() {
    WifiAudioStreamerTheme {
        ServerScreen()
    }
}

@Preview(showBackground = true)
@Composable
fun ModeSelectionScreenPreview() {
    WifiAudioStreamerTheme {
        ModeSelectionScreen(onServerClicked = {}, onClientClicked = {})
    }
}
