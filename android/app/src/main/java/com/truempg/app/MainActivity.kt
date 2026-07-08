package com.truempg.app

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.truempg.app.data.Trip
import com.truempg.app.ui.TrueMpgTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TrueMpgTheme {
                val vm: MainViewModel = viewModel()
                val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT)
                else
                    arrayOf(android.Manifest.permission.BLUETOOTH,
                        android.Manifest.permission.BLUETOOTH_ADMIN)

                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { vm.refreshDevices() }

                LaunchedEffect(Unit) { launcher.launch(permissions) }

                Surface(Modifier.fillMaxSize()) { AppRoot(vm) }
            }
        }
    }
}

@Composable
private fun AppRoot(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    var tab by remember { mutableStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                val items = listOf(
                    "Connect" to Icons.Filled.Bluetooth,
                    "Drive" to Icons.Filled.Speed,
                    "Trips" to Icons.Filled.List,
                    "Codes" to Icons.Filled.Warning,
                )
                items.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) },
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (tab) {
                0 -> ConnectScreen(vm, state)
                1 -> DriveScreen(vm, state)
                2 -> TripsScreen(vm, state)
                else -> CodesScreen(vm, state)
            }
        }
    }
}

@Composable
private fun ConnectScreen(vm: MainViewModel, state: UiState) {
    LaunchedEffect(Unit) { vm.refreshDevices() }
    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("TrueMPG", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(state.status, color = MaterialTheme.colorScheme.secondary)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.refreshDevices() }) { Text("Refresh") }
            if (state.connected) {
                OutlinedButton(onClick = { vm.disconnect() }) { Text("Disconnect") }
            }
        }

        if (!state.connected) {
            Text("Paired adapters", fontWeight = FontWeight.SemiBold)
            state.devices.forEach { d ->
                ElevatedCard(
                    onClick = { vm.connect(d.address) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(d.name, fontWeight = FontWeight.Bold)
                        Text(d.address, fontSize = 12.sp)
                    }
                }
            }
            if (state.connecting) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Connecting…")
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Text("Fuel model calibration", fontWeight = FontWeight.SemiBold)
        Text("Volumetric efficiency (VE): ${"%.2f".format(state.ve)}")
        Slider(
            value = state.ve.toFloat(),
            onValueChange = { vm.setVe(it.toDouble()) },
            valueRange = 0.5f..1.1f,
        )
        Text(
            "2.7L EcoBoost has no MAF sensor, so MPG is estimated by speed-density. " +
                "After a full tank, set VE = 0.85 × (logged gal ÷ pump gal).",
            fontSize = 12.sp
        )
    }
}

@Composable
private fun DriveScreen(vm: MainViewModel, state: UiState) {
    val r = state.readings
    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Trip average", color = MaterialTheme.colorScheme.secondary)
        Text(
            "${"%.1f".format(if (state.tripGallons > 1e-6) state.tripMiles / state.tripGallons else 0.0)} MPG",
            fontSize = 48.sp, fontWeight = FontWeight.Bold
        )
        Text("Instant: ${"%.1f".format(r.instMpg)} MPG", fontSize = 18.sp)
        Text(
            "trip ${"%.2f".format(state.tripMiles)} mi · " +
                "${"%.3f".format(state.tripGallons)} gal · ${state.tripElapsedSec}s"
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!state.tripActive) {
                Button(onClick = { vm.startTrip() }, enabled = state.connected) { Text("Start trip") }
            } else {
                Button(onClick = { vm.stopTrip() }) { Text("Stop & save trip") }
            }
        }

        HorizontalDivider()
        Text("Live data", fontWeight = FontWeight.SemiBold)
        Gauge("Speed", "%.0f mph".format(r.mph))
        Gauge("RPM", "%.0f".format(r.rpm))
        Gauge("MAP", "%.0f kPa".format(r.mapKpa))
        Gauge("Intake air", "%.0f °C".format(r.iatC))
        r.coolantC?.let { Gauge("Coolant", "%.0f °C".format(it)) }
        r.loadPct?.let { Gauge("Engine load", "%.0f %%".format(it)) }
        r.throttlePct?.let { Gauge("Throttle", "%.0f %%".format(it)) }
        r.fuelLevelPct?.let { Gauge("Fuel level", "%.0f %%".format(it)) }
        r.volts?.let { Gauge("Battery", "%.1f V".format(it)) }
        Gauge("Lambda", "%.3f".format(r.lambda))

        if (!state.connected) Text("Connect on the Connect tab first.",
            color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun Gauge(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TripsScreen(vm: MainViewModel, state: UiState) {
    val fmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("Trip history", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (state.trips.isNotEmpty())
                TextButton(onClick = { vm.clearTripHistory() }) { Text("Clear") }
        }
        if (state.trips.isEmpty()) {
            Text("No saved trips yet. Start one on the Drive tab.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.trips) { t -> TripRow(t, fmt.format(Date(t.startedAt))) }
            }
        }
    }
}

@Composable
private fun TripRow(t: Trip, dateLabel: String) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(dateLabel, fontWeight = FontWeight.Bold)
            Text("${"%.1f".format(t.avgMpg)} MPG avg", fontSize = 20.sp,
                color = MaterialTheme.colorScheme.secondary)
            Text("${"%.2f".format(t.miles)} mi · ${"%.3f".format(t.gallons)} gal · " +
                "${t.durationSec / 60}m ${t.durationSec % 60}s", fontSize = 12.sp)
        }
    }
}

@Composable
private fun CodesScreen(vm: MainViewModel, state: UiState) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Trouble codes", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.readDtcs() }, enabled = state.connected) { Text("Read codes") }
            OutlinedButton(onClick = { vm.clearDtcs() }, enabled = state.connected) { Text("Clear codes") }
        }
        if (state.dtcMessage.isNotEmpty()) Text(state.dtcMessage)
        state.dtcs.forEach { code ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Text(code, Modifier.padding(14.dp), fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        }
        if (!state.connected) Text("Connect first.", color = MaterialTheme.colorScheme.error)
        Text("Note: clearing turns the light off but does not fix the underlying " +
            "fault — real codes (like a catalyst code) will come back.", fontSize = 12.sp)
    }
}
