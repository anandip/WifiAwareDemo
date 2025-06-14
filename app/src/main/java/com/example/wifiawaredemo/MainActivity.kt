package com.example.wifiawaredemo

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.icu.text.SimpleDateFormat
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySession
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.wifiawaredemo.shared.LogViewModel
import com.example.wifiawaredemo.ui.theme.WifiAwareDemoTheme
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Inet6Address
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "WifiAwareDemo"
        private const val SERVICE_NAME = "com.example.wifiawaredemo"
    }

    private var wifiAwareSession: WifiAwareSession? = null
    private var publisherDiscoverySession: DiscoverySession? = null
    private var subscriberDiscoverySession: DiscoverySession? = null

    private val logViewModel: LogViewModel by viewModels<LogViewModel>()

    private val executorService: ExecutorService = Executors.newCachedThreadPool()


    enum class Mode { NONE, PUBLISHER, SUBSCRIBER }
    private var mode = Mode.NONE

    private val requestPermissionsLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.entries.all { it.value }
            if (allGranted) {
                log("All required permissions granted.")
                when (mode) {
                    Mode.PUBLISHER -> publish()
                    Mode.SUBSCRIBER -> subscribe()
                    else -> {}
                }
            } else {
                log("Some permissions were denied. Wi-Fi Aware may not function.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            WifiAwareDemoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    WifiAwareApp(
                        logViewModel,
                        onStartPublisher = {
                            mode = Mode.PUBLISHER
                            checkAndRequestPermissions { publish() }
                        },
                        onStartSubscriber = {
                            mode = Mode.SUBSCRIBER
                            checkAndRequestPermissions { subscribe() }
                        }
                    )
                }
            }
        }
    }

    //region Publisher
    private fun publish() {
        log("Publishing.")
        createSession(::publishService)
    }

    private fun createSession(onSessionAttached: () -> Unit) {
        var wifiAwareManager: WifiAwareManager? = null
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) {
            registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        log("Wi-Fi Aware state changed. Assuming not available.")
                        wifiAwareSession = null
                    }
                },
                IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED))
            wifiAwareManager =
                applicationContext.getSystemService(WIFI_AWARE_SERVICE) as WifiAwareManager
        } else {
            log("Wi-Fi Aware is not supported on this device.")
        }

        wifiAwareManager?.attach(object : AttachCallback() {
            override fun onAttached(session: WifiAwareSession) {
                log("Wi-Fi Aware session attached.")
                wifiAwareSession = session
                onSessionAttached()
            }

            override fun onAttachFailed() {
                log("Wi-Fi Aware attach failed.")
            }

            override fun onAwareSessionTerminated() {
                log("Wi-Fi Aware session terminated.")
            }
        }, null)
    }

    private fun publishService() {
        log("Publishing service.")
        val publishConfig = PublishConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .setPublishType(PublishConfig.PUBLISH_TYPE_SOLICITED)
            .build()

        try {
            wifiAwareSession?.publish(publishConfig, PublisherDiscoverySessionCallback(), null)
        } catch (e: SecurityException) {
            log("Permissions missing: ${e.message}")
        }
    }

    inner class PublisherDiscoverySessionCallback : DiscoverySessionCallback() {
        override fun onPublishStarted(session: PublishDiscoverySession) {
            publisherDiscoverySession = session
            log("Publish started. Waiting for subscribers.")
        }

        override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
            val msg = String(message)
            log("Publisher received message from ${peerHandle}: $msg")
            publisherDiscoverySession?.sendMessage(
                peerHandle, 1, "Hello from Publisher!".toByteArray())
            executorService.execute { startServerSocket(peerHandle) }
        }

        override fun onServiceLost(peerHandle: PeerHandle, reason:Int) {
            log("Publisher lost peer: $peerHandle")
        }

        override fun onMessageSendSucceeded(messageId: Int) {
            log("Publisher sent message $messageId successfully.")
        }

        override fun onMessageSendFailed(messageId: Int) {
            log("Publisher failed to send message $messageId")
        }
    }

    private fun startServerSocket(peerHandle: PeerHandle) {
        var serverSocket: ServerSocket? = null
        try {
            serverSocket = ServerSocket(0)
            log("Publisher: Server socket listening on port ${serverSocket.localPort}")

            requestNetwork(publisherDiscoverySession!!, peerHandle, serverSocket.localPort)

            val clientSocket: Socket = serverSocket.accept()
            log("Publisher: Client connected: ${clientSocket.inetAddress.hostAddress}")

            val reader = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
            val clientMessage = reader.readLine()
            log("Publisher received from client: $clientMessage")

            val writer = BufferedOutputStream(clientSocket.getOutputStream())
            val fragment = ByteArray(1024)
            (0..<1024).forEach { i -> fragment[i] = (i % 256).toByte() }
            (0..<10240).forEach { writer.write(fragment) }
            writer.close()
            log("Sent payload.")

            clientSocket.close()
            log("Publisher: Client socket closed.")

        } catch (e: Exception) {
            log("Publisher server socket error: ${e.message}")
            Log.e(TAG, "Publisher server socket error", e)
        } finally {
            try {
                serverSocket?.close()
                log("Publisher: Server socket closed.")
            } catch (e: Exception) {
                Log.e(TAG, "Error closing server socket", e)
            }
        }
    }

    private fun requestNetwork(
        session: DiscoverySession, peerHandle: PeerHandle, port: Int? = null) {
        log("Requesting Wi-Fi Aware network connection.")
        val isSubscriber = (port == null)
        val networkSpecifier = WifiAwareNetworkSpecifier.Builder(session, peerHandle)
            .setPskPassphrase("some_passphrase")
            .apply { if (port != null) setPort(port) }
            .build()

        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(networkSpecifier)
            .build()

        val connectivityManager =
            applicationContext.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

        connectivityManager.requestNetwork(
            networkRequest,
            NetworkConnectivityCallback(connectSocket = isSubscriber)
        )
    }

    inner class NetworkConnectivityCallback(private val connectSocket: Boolean) :
        ConnectivityManager.NetworkCallback() {
        private var connected = false

        override fun onAvailable(network: Network) {
            log("Wi-Fi Aware network available.")
        }

        override fun onUnavailable() {
            log("Wi-Fi Aware network unavailable.")
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            log("Wi-Fi Aware network capabilities changed.")
            if (!connectSocket || connected) {
                return
            }
            executorService.execute {
                log("Connecting socket.")
                val peerAwareInfo =
                    networkCapabilities.transportInfo as WifiAwareNetworkInfo
                val peerAddress = peerAwareInfo.peerIpv6Addr
                val peerPort = peerAwareInfo.port
                connected = true
                connectSocket(network, peerAddress!!, peerPort)
            }
        }

        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            log("Wi-Fi Aware network ${if (blocked) "BLOCKED" else "unblocked"}.")
        }

        override fun onLost(network: Network) {
            log("Wi-Fi Aware network lost.")
        }
    }
    //endregion Publisher

    //region Subscriber
    private fun subscribe() {
        log("Subscribing.")
        createSession(::subscribeToService)
    }

    private fun subscribeToService() {
        log("Subscribing to service.")
        val subscribeConfig = SubscribeConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_ACTIVE)
            .build()

        try {
            wifiAwareSession?.subscribe(subscribeConfig, SubscriberDiscoverySessionCallback(), null)
        } catch (e: SecurityException) {
            log("Permissions missing: ${e.message}")
        }
    }

    inner class SubscriberDiscoverySessionCallback : DiscoverySessionCallback() {
        override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
            subscriberDiscoverySession = session
            log("Subscribe started. Looking for publishers.")
        }

        override fun onServiceDiscovered(peerHandle: PeerHandle,
                                         serviceSpecificInfo: ByteArray,
                                         matchFilter: List<ByteArray>) {
            log("Found peer: $peerHandle")

            subscriberDiscoverySession?.sendMessage(
                peerHandle, 1, "Hello from Subscriber!".toByteArray())
        }

        override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
            val msg = String(message)
            log("Subscriber received message from peer ${peerHandle}: $msg")
            requestNetwork(subscriberDiscoverySession!!, peerHandle)
        }

        override fun onServiceLost(peerHandle: PeerHandle, reason: Int) {
            log("Service lost: $peerHandle")
        }

        override fun onMessageSendSucceeded(messageId: Int) {
            log("Subscriber sent message $messageId successfully.")
        }
    }

    private fun connectSocket(network: Network, peerAddress: Inet6Address, peerPort: Int) {
        var socket: Socket? = null
        try {
            socket = network.socketFactory.createSocket(peerAddress, peerPort)
            log("Socket connected.")

            val writer = PrintWriter(socket.getOutputStream(), true)
            writer.println("Hello!")

            val reader = BufferedInputStream(socket.getInputStream())
            val fragment = ByteArray(1024)
            var bytesRead = 0
            while(true) {
                val bufferBytesRead = reader.read(fragment)
                if (bufferBytesRead >= 0) {
                    bytesRead += bufferBytesRead
                } else {
                    break
                }
            }
            log("Received $bytesRead bytes.")

            socket.close()

            log("Socket closed.")

        } catch (e: Exception) {
            log("Socket error: ${e.message}")
            Log.e(TAG, "Socket error", e)
        } finally {
            try {
                socket?.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing socket", e)
            }
        }
    }
    //endregion Subscriber

    override fun onDestroy() {
        super.onDestroy()
        publisherDiscoverySession?.close()
        subscriberDiscoverySession?.close()
        wifiAwareSession?.close()
        executorService.shutdownNow()
    }

    private fun checkAndRequestPermissions(onPermissionsGranted: () -> Unit) {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13 (API 33) and higher
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        } else {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        if (checkSelfPermission(Manifest.permission.CHANGE_WIFI_STATE) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.CHANGE_WIFI_STATE)
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_WIFI_STATE) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_WIFI_STATE)
        }

        if (permissionsToRequest.isNotEmpty()) {
            log("Requesting permissions: ${permissionsToRequest.joinToString()}")
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            onPermissionsGranted()
        }
    }

    private fun log(message: String) {
        val timestamp = SimpleDateFormat.getTimeInstance().format(System.currentTimeMillis())
        logViewModel.addMessage("[$timestamp] $message")
        Log.i(TAG, message)
    }

}

@Composable
fun WifiAwareApp(
    logViewModel: LogViewModel,
    onStartPublisher: () -> Unit,
    onStartSubscriber: () -> Unit
) {
    val lazyListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f), // Make LazyColumn take available height
            state = lazyListState
        ) {
            itemsIndexed(logViewModel.messages) { index, message ->
                Text(message, Modifier.padding(1.dp))
                HorizontalDivider(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 1.dp)
                )
            }
        }

        Row(Modifier
            .fillMaxWidth()
            .padding(30.dp),
            horizontalArrangement = Arrangement.SpaceEvenly) {
            Button(
                onClick = onStartPublisher,
            ) {
                Text("Publish")
            }
            Button(
                onClick = onStartSubscriber,
            ) {
                Text("Subscribe")
            }
        }
    }

    LaunchedEffect(logViewModel.messages.size) {
        if (logViewModel.messages.isNotEmpty()) {
            coroutineScope.launch {
                lazyListState.animateScrollToItem(logViewModel.messages.size - 1)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
@SuppressLint("ViewModelConstructorInComposable")
fun WifeAwareAppPreview() {
    WifiAwareDemoTheme {
        val logViewModel = LogViewModel()
        for (i in 1..20) {
            logViewModel.addMessage("Message $i")
        }
        WifiAwareApp(logViewModel, {}, {})
    }
}