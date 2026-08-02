package com.example.stsprint

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Handler
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Phonelink
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.stsprint.ui.theme.StatusError
import com.example.stsprint.ui.theme.StatusInfo
import com.example.stsprint.ui.theme.StatusOk
import com.example.stsprint.ui.theme.StatusWarn
import com.example.stsprint.ui.theme.StsprintGreen
import com.example.stsprint.ui.theme.StsprintGreenDark
import com.example.stsprint.ui.theme.StsprintGreenSoft
import com.example.stsprint.ui.theme.StsprintTheme
import kotlinx.coroutines.delay

private const val PRINT_SERVER_PORT = 12345

class MainActivity : ComponentActivity() {

    private var printBridgeService: PrintBridgeService? = null
    private var bound = false
    private val serviceState = mutableStateOf<PrintBridgeService?>(null)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? PrintBridgeService.LocalBinder
            printBridgeService = binder?.getService()
            bound = true
            serviceState.value = printBridgeService
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            printBridgeService = null
            bound = false
            serviceState.value = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
            }
        }
        try {
            val intent = Intent(this, PrintBridgeService::class.java)
            startForegroundService(intent)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to start service", e)
        }

        setContent {
            StsprintTheme {
                val service by serviceState
                MainScreen(service = service, context = this@MainActivity)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (bound) {
            printBridgeService?.ensureServerRunning()
            printBridgeService?.tryConnectToPrinter()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
    }
}

private enum class BridgeTab(val label: String) {
    DASHBOARD("Dashboard"),
    PRINT("Print"),
    CALLER_ID("Caller ID"),
    CALLS("Calls"),
    SETTINGS("Settings")
}

fun hapticFeedback(context: Context, duration: Long = 50) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.vibrate(
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
        )
    }
}

@Composable
fun MainScreen(service: PrintBridgeService? = null, context: Context? = null) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = BridgeTab.entries

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(StsprintGreenSoft, MaterialTheme.colorScheme.background)
                        )
                    )
            ) {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                    Text(
                        text = "STS Device Bridge",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "ScanToServe POS · powered by santoserve",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.primary,
                    indicator = { positions ->
                        if (selectedTab < positions.size) {
                            TabRowDefaults.SecondaryIndicator(
                                modifier = Modifier.tabIndicatorOffset(positions[selectedTab]),
                                height = 3.dp,
                                color = StsprintGreen
                            )
                        }
                    },
                    divider = {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                    }
                ) {
                    tabs.forEachIndexed { index, tab ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = {
                                selectedTab = index
                                context?.let { hapticFeedback(it) }
                            },
                            icon = {
                                Icon(
                                    imageVector = when (tab) {
                                        BridgeTab.DASHBOARD -> Icons.Filled.Dashboard
                                        BridgeTab.PRINT -> Icons.Filled.Print
                                        BridgeTab.CALLER_ID -> Icons.Filled.Phonelink
                                        BridgeTab.CALLS -> Icons.Filled.History
                                        BridgeTab.SETTINGS -> Icons.Filled.Settings
                                    },
                                    contentDescription = "${tab.label} tab",
                                    tint = if (selectedTab == index) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            },
                            text = {
                                Text(
                                    text = tab.label,
                                    fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Medium,
                                    color = if (selectedTab == index) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        when (tabs[selectedTab]) {
            BridgeTab.DASHBOARD -> DashboardTab(
                modifier = Modifier.padding(innerPadding),
                service = service,
                context = context
            )
            BridgeTab.PRINT -> PrintTab(
                modifier = Modifier.padding(innerPadding),
                service = service,
                context = context
            )
            BridgeTab.CALLER_ID -> {
                var serverPort by remember { mutableIntStateOf(12345) }
                LaunchedEffect(service) {
                    while (true) {
                        serverPort = service?.getServerPort() ?: 12345
                        delay(500)
                    }
                }
                CallerIdTab(
                    modifier = Modifier.padding(innerPadding),
                    service = service,
                    context = context,
                    serverPort = serverPort
                )
            }
            BridgeTab.CALLS -> CallHistoryTab(
                modifier = Modifier.padding(innerPadding),
                service = service,
                context = context
            )
            BridgeTab.SETTINGS -> SettingsTab(
                modifier = Modifier.padding(innerPadding),
                service = service,
                context = context
            )
        }
    }
}

@Composable
private fun PrintTab(
    modifier: Modifier = Modifier,
    service: PrintBridgeService?,
    context: Context? = null
) {
    var printerConnected by remember { mutableStateOf(false) }
    var hasCandidate by remember { mutableStateOf(false) }
    var isConnecting by remember { mutableStateOf(false) }
    var testPrintLoading by remember { mutableStateOf(false) }
    var serverPort by remember { mutableIntStateOf(12345) }
    var serverError by remember { mutableStateOf<String?>(null) }
    var isReconnecting by remember { mutableStateOf(false) }

    LaunchedEffect(service) {
        while (true) {
            printerConnected = service?.isPrinterConnected() == true
            hasCandidate = service?.hasPrinterCandidate() == true
            serverPort = service?.getServerPort() ?: 12345
            delay(500)
        }
    }

    val scroll = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        StatusBanner(
            title = if (printerConnected) "Printer connected" else if (hasCandidate) "USB permission needed" else "Waiting for printer",
            subtitle = when {
                printerConnected -> "Ready to receive jobs from ScanToServe"
                hasCandidate -> "Allow USB access when Android prompts you"
                else -> "Connect a USB receipt printer to this device"
            },
            tone = when {
                printerConnected -> BannerTone.OK
                hasCandidate -> BannerTone.WARN
                else -> BannerTone.INFO
            }
        )

        if (serverError != null) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = StatusError.copy(alpha = 0.12f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Server Error",
                            style = MaterialTheme.typography.titleMedium,
                            color = StatusError
                        )
                        Text(
                            text = serverError ?: "Unknown error",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Button(
                        onClick = {
                            context?.let { hapticFeedback(it) }
                            service?.ensureServerRunning()
                            serverError = null
                        },
                        modifier = Modifier.padding(start = 12.dp),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text("Retry", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            shadowElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Print,
                        contentDescription = "Print server icon",
                        modifier = Modifier
                            .size(20.dp)
                            .padding(end = 8.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Local print server",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "http://localhost:$serverPort",
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Chrome / PWA posts ESC/POS bytes to POST /print",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Usb,
                        contentDescription = "USB printer access icon",
                        modifier = Modifier
                            .size(20.dp)
                            .padding(end = 8.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "USB printer access",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                PermissionStep(
                    number = 1,
                    title = "Connect printer",
                    body = "Plug in the USB receipt printer (OTG if needed) and power it on.",
                    done = hasCandidate || printerConnected
                )
                PermissionStep(
                    number = 2,
                    title = "Allow USB permission",
                    body = "When Android asks, tap Allow so STS Device Bridge can talk to the printer.",
                    done = printerConnected,
                    active = hasCandidate && !printerConnected
                )
                PermissionStep(
                    number = 3,
                    title = "Keep bridge running",
                    body = "Leave this app / notification active. Do not force-stop it.",
                    done = true,
                    active = printerConnected
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            isConnecting = true
                            context?.let { hapticFeedback(it) }
                            service?.tryConnectToPrinter()
                        },
                        enabled = service != null && !isConnecting,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(vertical = 14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Usb,
                            contentDescription = "USB connection icon",
                            modifier = Modifier
                                .size(18.dp)
                                .padding(end = 8.dp)
                        )
                        Text(
                            text = if (isConnecting) "Requesting..." else if (printerConnected) "Reconnect" else "Connect",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            context?.let { hapticFeedback(it) }
                            Toast.makeText(context, "Testing server connection...", Toast.LENGTH_SHORT).show()
                            Thread {
                                try {
                                    val socket = java.net.Socket()
                                    socket.connect(java.net.InetSocketAddress("127.0.0.1", serverPort), 1000)
                                    socket.close()
                                    Handler(android.os.Looper.getMainLooper()).post {
                                        Toast.makeText(context, "✓ Server is reachable", Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    Handler(android.os.Looper.getMainLooper()).post {
                                        Toast.makeText(context, "✗ Server not reachable", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }.start()
                        },
                        enabled = service != null,
                        modifier = Modifier.weight(0.8f),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(vertical = 14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Speed,
                            contentDescription = "Test icon",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                if (printerConnected) {
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            testPrintLoading = true
                            context?.let { hapticFeedback(it) }
                            service?.testPrint()
                            testPrintLoading = false
                            context?.let { hapticFeedback(it) }
                        },
                        enabled = service != null && !testPrintLoading,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Print,
                            contentDescription = "Test print icon",
                            modifier = Modifier
                                .size(16.dp)
                                .padding(end = 6.dp)
                        )
                        Text(
                            text = if (testPrintLoading) "Printing..." else "Test Print",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "Printer not working?",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(10.dp))
                TipItem(number = 1, text = stringResource(R.string.main_tip_1))
                TipItem(number = 2, text = stringResource(R.string.main_tip_2))
                TipItem(number = 3, text = stringResource(R.string.main_tip_3))
                TipItem(number = 4, text = stringResource(R.string.main_tip_4))
                TipItem(number = 5, text = stringResource(R.string.main_tip_5))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun CallerIdTab(
    modifier: Modifier = Modifier,
    service: PrintBridgeService?,
    context: Context? = null,
    serverPort: Int = 12345
) {
    var snapshot by remember { mutableStateOf(CallerIdSnapshot()) }
    var isScanning by remember { mutableStateOf(false) }

    LaunchedEffect(service) {
        var wasListening = false
        while (true) {
            snapshot = service?.getCallerIdSnapshot() ?: CallerIdSnapshot()
            if (snapshot.listening && !wasListening) {
                context?.let { hapticFeedback(it) }
                wasListening = true
            } else if (!snapshot.listening && wasListening) {
                context?.let { hapticFeedback(it) }
                wasListening = false
            }
            delay(400)
        }
    }

    val scroll = rememberScrollState()
    val selected = snapshot.selectedDevice
    val permissionGranted = selected?.hasPermission == true

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        StatusBanner(
            title = callerIdStatusTitle(snapshot),
            subtitle = snapshot.statusMessage,
            tone = callerIdTone(snapshot)
        )

        if (snapshot.errorMessage != null) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = StatusError.copy(alpha = 0.12f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = "Error icon",
                        tint = StatusError,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Error",
                            style = MaterialTheme.typography.titleMedium,
                            color = StatusError
                        )
                        Text(
                            text = snapshot.errorMessage ?: "Unknown error",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            shadowElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Phonelink,
                        contentDescription = null,
                        modifier = Modifier
                            .size(20.dp)
                            .padding(end = 8.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "USB Caller ID access",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Text(
                    text = "Separate from the printer. Grant USB permission for the CID Easy device only.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                PermissionStep(
                    number = 1,
                    title = "Scan USB devices",
                    body = "Find CID Easy among connected USB hardware.",
                    done = snapshot.devices.isNotEmpty()
                )
                PermissionStep(
                    number = 2,
                    title = "Select Caller ID device",
                    body = "Tap the CID Easy row — not the receipt printer.",
                    done = selected != null,
                    active = snapshot.devices.isNotEmpty() && selected == null
                )
                PermissionStep(
                    number = 3,
                    title = "Allow USB permission",
                    body = "Android will show a system dialog. Tap Allow / OK.",
                    done = permissionGranted,
                    active = selected != null && !permissionGranted
                )
                PermissionStep(
                    number = 4,
                    title = "Start listening",
                    body = "Capture raw USB bytes when a call comes in.",
                    done = snapshot.listening,
                    active = permissionGranted && !snapshot.listening
                )

                Button(
                    onClick = {
                        isScanning = true
                        context?.let { hapticFeedback(it) }
                        service?.scanCallerIdDevices()
                        isScanning = false
                    },
                    enabled = service != null && !isScanning,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(vertical = 14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = "Scan USB devices icon",
                        modifier = Modifier
                            .size(18.dp)
                            .padding(end = 8.dp)
                    )
                    Text(
                        text = if (isScanning) "Scanning..." else "Scan USB devices",
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                if (selected != null && !permissionGranted) {
                    Button(
                        onClick = {
                            context?.let { hapticFeedback(it) }
                            service?.requestCallerIdPermission(selected.deviceId)
                        },
                        enabled = service != null,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = StatusWarn,
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(vertical = 14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = "Lock icon for USB permission",
                            modifier = Modifier
                                .size(18.dp)
                                .padding(end = 8.dp),
                            tint = Color.White
                        )
                        Text("Allow USB access for Caller ID", style = MaterialTheme.typography.labelLarge)
                    }
                }

                if (selected != null && permissionGranted && !snapshot.listening) {
                    Button(
                        onClick = {
                            context?.let { hapticFeedback(it) }
                            service?.startCallerIdListening(selected.deviceId)
                        },
                        enabled = service != null,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(vertical = 14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Speed,
                            contentDescription = "Speed icon for listening",
                            modifier = Modifier
                                .size(18.dp)
                                .padding(end = 8.dp)
                        )
                        Text("Start listening", style = MaterialTheme.typography.labelLarge)
                    }
                }

                if (snapshot.listening) {
                    OutlinedButton(
                        onClick = {
                            context?.let { hapticFeedback(it) }
                            service?.stopCallerIdListening()
                        },
                        enabled = service != null,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(vertical = 14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Check icon for stop",
                            modifier = Modifier
                                .size(18.dp)
                                .padding(end = 8.dp),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                        Text("Stop listening", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        modifier = Modifier
                            .size(20.dp)
                            .padding(end = 8.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Connected USB devices",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (snapshot.devices.isEmpty()) {
                        "No devices yet — connect CID Easy, then scan."
                    } else {
                        "${snapshot.devices.size} device(s) found. Select the Caller ID unit."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                snapshot.devices.forEach { device ->
                    DeviceRow(
                        device = device,
                        selected = device.deviceId == snapshot.selectedDeviceId,
                        onSelect = { service?.selectCallerIdDevice(device.deviceId) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }

        if (selected != null) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = null,
                            modifier = Modifier
                                .size(20.dp)
                                .padding(end = 8.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Device details",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    DetailRow("Name", selected.displayName)
                    DetailRow("VID", "${selected.vendorIdHex} (${selected.vendorId})")
                    DetailRow("PID", "${selected.productIdHex} (${selected.productId})")
                    DetailRow("Class", selected.deviceClassLabel)
                    DetailRow(
                        "USB permission",
                        if (selected.hasPermission) "Granted" else "Required"
                    )
                    DetailRow("Interfaces", selected.interfaceCount.toString())
                    selected.suspectedChipHint?.let {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    MonoBlock(
                        text = buildString {
                            selected.interfaces.forEach { iface ->
                                appendLine(
                                    "IF[${iface.index}] ${iface.interfaceClassLabel} " +
                                        "sub=${iface.interfaceSubclass} proto=${iface.interfaceProtocol}"
                                )
                                iface.endpoints.forEach { ep ->
                                    appendLine(
                                        "  EP 0x%02X %s %s max=%d int=%d".format(
                                            ep.address,
                                            ep.direction,
                                            ep.type,
                                            ep.maxPacketSize,
                                            ep.interval
                                        )
                                    )
                                }
                            }
                        }
                    )
                    if (snapshot.candidateEndpoints.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Listening candidates",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = snapshot.candidateEndpoints.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Speed,
                            contentDescription = "Raw data icon",
                            modifier = Modifier
                                .size(20.dp)
                                .padding(end = 8.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Raw USB data",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            context?.let { hapticFeedback(it) }
                            service?.clearCallerIdLogs()
                        },
                        enabled = service != null,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Clear", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                val logSize = (snapshot.asciiLog.length + snapshot.hexLog.length) / 1024

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Storing last 7 days",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "~${logSize}KB",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (snapshot.packets.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Oldest: ${snapshot.packets.firstOrNull()?.timestampIso ?: "N/A"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                if (logSize > 500) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = StatusWarn.copy(alpha = 0.12f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Info,
                                contentDescription = "Memory warning icon",
                                tint = StatusWarn,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Logs using ~${logSize}KB. Clear logs if memory is low.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Text("ASCII", style = MaterialTheme.typography.labelLarge)
                MonoBlock(
                    text = snapshot.asciiLog.ifBlank {
                        "No data yet. Start listening, then call the restaurant line."
                    },
                    minHeight = 110
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text("HEX", style = MaterialTheme.typography.labelLarge)
                MonoBlock(
                    text = snapshot.hexLog.ifBlank { "No data yet." },
                    minHeight = 110
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "http://localhost:$serverPort/api/caller-id/status\n" +
                        "http://localhost:$serverPort/api/caller-id/diagnostics",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

private enum class BannerTone { OK, WARN, ERROR, INFO }

@Composable
private fun StatusBanner(
    title: String,
    subtitle: String,
    tone: BannerTone
) {
    val color = when (tone) {
        BannerTone.OK -> StatusOk
        BannerTone.WARN -> StatusWarn
        BannerTone.ERROR -> StatusError
        BannerTone.INFO -> StatusInfo
    }
    val bg = color.copy(alpha = 0.12f)
    val icon = when (tone) {
        BannerTone.OK -> Icons.Filled.Check
        BannerTone.WARN -> Icons.Filled.Info
        BannerTone.ERROR -> Icons.Filled.Info
        BannerTone.INFO -> Icons.Filled.Info
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = bg,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = "$title status icon",
                tint = color,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun PermissionStep(
    number: Int,
    title: String,
    body: String,
    done: Boolean,
    active: Boolean = false
) {
    val accent by animateColorAsState(
        targetValue = when {
            done -> StatusOk
            active -> StsprintGreen
            else -> MaterialTheme.colorScheme.outline
        },
        label = "stepAccent"
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(if (done || active) accent else MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (done) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "Step completed",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            } else {
                Text(
                    text = number.toString(),
                    color = if (active) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun DeviceRow(
    device: UsbDeviceInfo,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val border = if (selected) StsprintGreen else MaterialTheme.colorScheme.outline.copy(alpha = 0.7f)
    val bg = if (selected) StsprintGreenSoft else MaterialTheme.colorScheme.surfaceVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.5.dp, border, RoundedCornerShape(14.dp))
            .background(bg)
            .clickable(onClick = onSelect)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) StsprintGreen.copy(alpha = 0.2f)
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Usb,
                    contentDescription = "USB device icon",
                    tint = if (selected) StsprintGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                )
                Text(
                    text = buildString {
                        append(device.vendorIdHex)
                        append(" · ")
                        append(device.productIdHex)
                        if (device.looksLikePrinter) append(" · printer?")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            PermissionChip(granted = device.hasPermission)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "${device.deviceClassLabel} · ${device.interfaceCount} interface(s)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PermissionChip(granted: Boolean) {
    val color = if (granted) StatusOk else StatusWarn
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.14f)
    ) {
        Text(
            text = if (granted) "USB allowed" else "Permission needed",
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

@Composable
private fun MonoBlock(text: String, minHeight: Int = 80) {
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight.dp, max = 200.dp)
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(10.dp)
            .verticalScroll(vScroll)
            .horizontalScroll(hScroll)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun TipItem(number: Int, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(StsprintGreen),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color.Black
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun callerIdStatusTitle(snapshot: CallerIdSnapshot): String = when (snapshot.status) {
    CallerIdStatus.NOT_DETECTED -> "Caller ID not detected"
    CallerIdStatus.DETECTED -> "USB devices detected"
    CallerIdStatus.PERMISSION_REQUIRED -> "USB permission required"
    CallerIdStatus.CONNECTED -> "Caller ID ready"
    CallerIdStatus.LISTENING -> "Listening for call data"
    CallerIdStatus.DISCONNECTED -> "Caller ID disconnected"
    CallerIdStatus.ERROR -> "Caller ID error"
}

private fun callerIdTone(snapshot: CallerIdSnapshot): BannerTone = when (snapshot.status) {
    CallerIdStatus.LISTENING, CallerIdStatus.CONNECTED -> BannerTone.OK
    CallerIdStatus.PERMISSION_REQUIRED, CallerIdStatus.DETECTED -> BannerTone.WARN
    CallerIdStatus.ERROR, CallerIdStatus.DISCONNECTED -> BannerTone.ERROR
    CallerIdStatus.NOT_DETECTED -> BannerTone.INFO
}

@Composable
private fun DashboardTab(
    modifier: Modifier = Modifier,
    service: PrintBridgeService?,
    context: Context? = null
) {
    var printerConnected by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var lastCall by remember { mutableStateOf<String?>(null) }
    var callCount by remember { mutableLongStateOf(0L) }
    var printCount by remember { mutableLongStateOf(0L) }
    var uptime by remember { mutableStateOf("0 min") }

    LaunchedEffect(service) {
        while (true) {
            printerConnected = service?.isPrinterConnected() == true
            val snapshot = service?.getCallerIdSnapshot()
            listening = snapshot?.listening == true
            lastCall = service?.getCallHistory()?.firstOrNull()?.phone
            callCount = service?.getStats()?.getCallCount() ?: 0L
            printCount = service?.getStats()?.getPrintJobCount() ?: 0L
            uptime = service?.getStats()?.getUptimeString() ?: "0 min"
            delay(500)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Overview",
            style = MaterialTheme.typography.headlineMedium
        )

        // Status cards
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatusCard(
                title = "Printer",
                status = if (printerConnected) "Connected" else "Disconnected",
                icon = Icons.Filled.Print,
                isActive = printerConnected,
                modifier = Modifier.weight(1f)
            )
            StatusCard(
                title = "Caller ID",
                status = if (listening) "Listening" else "Idle",
                icon = Icons.Filled.Phonelink,
                isActive = listening,
                modifier = Modifier.weight(1f)
            )
        }

        // Last call
        if (lastCall != null) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Last Call", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = lastCall ?: "—",
                        style = MaterialTheme.typography.headlineSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Button(
                        onClick = { context?.let { Toast.makeText(it, "Copied!", Toast.LENGTH_SHORT).show() } },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Print,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // Stats
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                label = "Calls",
                value = callCount.toString(),
                icon = Icons.Filled.Phonelink,
                modifier = Modifier.weight(1f)
            )
            StatCard(
                label = "Prints",
                value = printCount.toString(),
                icon = Icons.Filled.Print,
                modifier = Modifier.weight(1f)
            )
            StatCard(
                label = "Uptime",
                value = uptime,
                icon = Icons.Filled.Speed,
                modifier = Modifier.weight(1f)
            )
        }

        // Quick actions
        Text("Quick Actions", style = MaterialTheme.typography.titleMedium)
        Button(
            onClick = { context?.let { hapticFeedback(it) }; service?.testPrint() },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Filled.Print, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Test Print")
        }
        Button(
            onClick = { context?.let { hapticFeedback(it) }; service?.scanCallerIdDevices() },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Scan Devices")
        }
    }
}

@Composable
private fun StatusCard(
    title: String,
    status: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isActive) StatusOk.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surface,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = if (isActive) StatusOk else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(6.dp))
            Text(title, style = MaterialTheme.typography.labelSmall)
            Text(status, style = MaterialTheme.typography.labelMedium, color = if (isActive) StatusOk else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatCard(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(6.dp))
            Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CallHistoryTab(
    modifier: Modifier = Modifier,
    service: PrintBridgeService?,
    context: Context? = null
) {
    var calls by remember { mutableStateOf<List<CallRecord>>(emptyList()) }

    LaunchedEffect(service) {
        while (true) {
            calls = service?.getCallHistory() ?: emptyList()
            delay(1000)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Recent Calls (${calls.size})", style = MaterialTheme.typography.headlineMedium)
            OutlinedButton(
                onClick = { service?.clearCallHistory() },
                enabled = calls.isNotEmpty(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Clear", style = MaterialTheme.typography.labelSmall)
            }
        }

        if (calls.isEmpty()) {
            Text("No calls recorded", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(calls) { call ->
                    CallHistoryItem(call, service)
                }
            }
        }
    }
}

@Composable
private fun CallHistoryItem(call: CallRecord, service: PrintBridgeService?) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(call.phone, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
                Text(call.timestamp.takeLast(8), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = { service?.deleteCallRecord(call.id) }, shape = RoundedCornerShape(8.dp)) {
                Text("×", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun SettingsTab(
    modifier: Modifier = Modifier,
    service: PrintBridgeService?,
    context: Context? = null
) {
    val settings = service?.getSettings()
    var usbTimeout by remember { mutableIntStateOf(settings?.getUsbTimeoutMs() ?: 5000) }
    var logRetentionDays by remember { mutableIntStateOf(settings?.getLogRetentionDays() ?: 7) }
    var autoReconnect by remember { mutableStateOf(settings?.isAutoReconnectEnabled() ?: true) }
    var notifications by remember { mutableStateOf(settings?.isNotificationsEnabled() ?: true) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        SettingItem("Auto-reconnect", "", autoReconnect) { value ->
            autoReconnect = value
            settings?.setAutoReconnect(value)
        }

        SettingItem("Notifications", "", notifications) { value ->
            notifications = value
            settings?.setNotifications(value)
        }

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Log Retention Days: $logRetentionDays", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = logRetentionDays.toFloat(),
                    onValueChange = { newValue ->
                        logRetentionDays = newValue.toInt()
                        settings?.setLogRetentionDays(logRetentionDays)
                    },
                    valueRange = 1f..30f,
                    steps = 29
                )
            }
        }

        Button(
            onClick = { service?.clearCallHistory(); context?.let { Toast.makeText(it, "History cleared", Toast.LENGTH_SHORT).show() } },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = StatusWarn)
        ) {
            Text("Clear Call History", style = MaterialTheme.typography.labelLarge)
        }

        Button(
            onClick = { service?.getStats()?.reset(); context?.let { Toast.makeText(it, "Stats reset", Toast.LENGTH_SHORT).show() } },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = StatusError)
        ) {
            Text("Reset All Stats", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun SettingItem(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelLarge)
            }
            androidx.compose.material3.Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    StsprintTheme {
        MainScreen()
    }
}
