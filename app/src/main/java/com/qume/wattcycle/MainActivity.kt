package com.qume.wattcycle

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {

    private lateinit var bleManager: BleManager

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            bleManager.startScan()
        } else {
            Toast.makeText(this, "Permissions required to scan for BLE", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bleManager = BleManager(this)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppContent(bleManager)
                }
            }
        }

        val requiredPermissions = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
        permissionLauncher.launch(requiredPermissions)
    }

    override fun onDestroy() {
        super.onDestroy()
        bleManager.stopScan()
    }
}

@Composable
fun AppContent(bleManager: BleManager) {
    var showLogs by remember { mutableStateOf(false) }
    val batteries by bleManager.batteries.collectAsState()
    val logs by bleManager.logs.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = "Wattcycle", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Button(onClick = { showLogs = !showLogs }) {
                Text(if (showLogs) "Batteries" else "Logs")
            }
        }

        if (showLogs) {
            LogView(logs, bleManager)
        } else {
            BatteryList(batteries.values.toList())
        }
    }
}

@Composable
fun BatteryList(batteries: List<BatteryData>) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        items(batteries) { battery ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = "${battery.name} (${battery.macAddress})", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    if (battery.isConnected) {
                        Text(text = "Status: Connected", color = Color(0xFF4CAF50))
                        Text(text = "SoC: ${battery.soc}%")
                        Text(text = "Voltage: ${String.format("%.2f", battery.voltage)} V")
                        Text(text = "Current: ${String.format("%.2f", battery.current)} A")
                        val watts = battery.voltage * battery.current
                        val state = if (battery.current > 0) "Charging" else if (battery.current < 0) "Discharging" else "Idle"
                        Text(text = "Power: ${String.format("%.2f", watts)} W ($state)")
                        Text(text = "Temp: ${String.format("%.1f", battery.temperature)} °C")
                        Text(text = "Cycles: ${battery.cycleCount}")
                    } else if (battery.isConnecting) {
                        Text(text = "Status: Connecting...", color = Color.Gray)
                    } else {
                        Text(text = "Status: Found, ready to connect", color = Color.Gray)
                    }
                }
            }
        }
    }
}

@Composable
fun LogView(logs: List<String>, bleManager: BleManager) {
    val context = androidx.compose.ui.platform.LocalContext.current
    
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Button(
            onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Logs", logs.joinToString("\n"))
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Copy Logs")
        }
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFEEEEEE))
                .padding(8.dp)
        ) {
            items(logs) { logMsg ->
                Text(text = logMsg, fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}
