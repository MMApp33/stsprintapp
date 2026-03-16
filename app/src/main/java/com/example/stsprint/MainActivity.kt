package com.example.stsprint

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.stsprint.ui.theme.StsprintTheme

private const val USB_PERMISSION_ACTION = "com.example.stsprint.USB_PERMISSION"
private const val PRINT_SERVER_PORT = 12345

class MainActivity : ComponentActivity() {

    private var usbManager: UsbManager? = null
    private var printerManager: UsbPrinterManager? = null
    private var printServer: PrintServer? = null
    private var receiverRegistered = false

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == USB_PERMISSION_ACTION) {
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    printerManager?.connect()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        try {
            val usb = getSystemService(Context.USB_SERVICE) as? UsbManager
            if (usb != null) {
                usbManager = usb
                printerManager = UsbPrinterManager(usb)
                tryConnectToPrinter()
                printServer = PrintServer(PRINT_SERVER_PORT, printerManager!!).apply {
                    start()
                }
                ContextCompat.registerReceiver(
                    this,
                    usbPermissionReceiver,
                    IntentFilter(USB_PERMISSION_ACTION),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                receiverRegistered = true
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Setup failed", e)
        }

        setContent {
            StsprintTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        tryConnectToPrinter()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (receiverRegistered) {
            try {
                unregisterReceiver(usbPermissionReceiver)
            } catch (_: Exception) { }
        }
        try {
            printServer?.stop()
        } catch (_: Exception) { }
        try {
            printerManager?.disconnect()
        } catch (_: Exception) { }
    }

    private fun tryConnectToPrinter() {
        val pm = printerManager ?: return
        val um = usbManager ?: return
        if (pm.connect()) return
        val device = pm.getFirstPrinterDevice() ?: return
        val intent = Intent(USB_PERMISSION_ACTION).apply { setPackage(packageName) }
        val permissionIntent = android.app.PendingIntent.getBroadcast(
            this, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE
        )
        um.requestPermission(device, permissionIntent)
    }
}

@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(20.dp)
    ) {
        // App title — this is the landing page
        Text(
            text = "STS Print Bridge",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Print app · Powered by santoserve",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
        )
        // Status card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.main_status_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = context.getString(R.string.main_status_url, PRINT_SERVER_PORT),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Keep app open — important notice
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "⚠️",
                        fontSize = 24.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.main_keep_open_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.main_keep_open_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    lineHeight = 22.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Printer troubleshooting
        Text(
            text = stringResource(R.string.main_troubleshoot_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = stringResource(R.string.main_troubleshoot_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        TipItem(number = 1, text = stringResource(R.string.main_tip_1))
        TipItem(number = 2, text = stringResource(R.string.main_tip_2))
        TipItem(number = 3, text = stringResource(R.string.main_tip_3))
        TipItem(number = 4, text = stringResource(R.string.main_tip_4))
        TipItem(number = 5, text = stringResource(R.string.main_tip_5))

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun TipItem(number: Int, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Card(
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier.size(28.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Text(
                    text = number.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
        Spacer(modifier = Modifier.size(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            lineHeight = 22.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    StsprintTheme {
        MainScreen()
    }
}
