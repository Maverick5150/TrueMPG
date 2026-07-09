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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.viewmodel.compose.viewModel
import com.truempg.app.data.Trip
import com.truempg.app.obd.DtcDescriptions
import com.truempg.app.obd.ObdMath
import com.truempg.app.obd.ObdMath.MpgMethod
import com.truempg.app.obd.PidRegistry
import com.truempg.app.obd.PidSpec
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
                val permissions = buildList {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        add(android.Manifest.permission.BLUETOOTH_CONNECT)
                        add(android.Manifest.permission.BLUETOOTH_SCAN)
                    } else {
                        add(android.Manifest.permission.BLUETOOTH)
                        add(android.Manifest.permission.BLUETOOTH_ADMIN)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(android.Manifest.permission.POST_NOTIFICATIONS)
                    }
                }.toTypedArray()

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
                    "Live" to Icons.Filled.Sensors,
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
                2 -> LiveScreen(vm, state)
                3 -> TripsScreen(vm, state)
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
        Text("Automatic operation", fontWeight = FontWeight.SemiBold)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Auto-connect on truck start")
            Switch(checked = state.autoConnect, onCheckedChange = { vm.setAutoConnect(it) })
        }
        state.savedAdapter?.let { Text("Saved adapter: $it", fontSize = 12.sp) }
        val ctx = LocalContext.current
        OutlinedButton(onClick = {
            try {
                ctx.startActivity(
                    Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + ctx.packageName)
                    )
                )
            } catch (e: Exception) {
                try { ctx.startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
            }
        }) { Text("Allow background running") }
        Text(
            "Auto-connect starts monitoring the moment the truck powers up the " +
                "adapter. \"Allow background running\" keeps the service alive mid-drive.",
            fontSize = 12.sp
        )

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Text("Vehicle", fontWeight = FontWeight.SemiBold)
        if (state.connected) {
            Text("${state.vehicleLabel}${state.vin?.let { " · VIN $it" } ?: ""}", fontSize = 12.sp)
            Text("Protocol ${state.protocol ?: "?"} · ${state.supportedPids.size} PIDs supported",
                fontSize = 12.sp)
        } else {
            Text("Connect to auto-detect the protocol, read the VIN, and discover PIDs.",
                fontSize = 12.sp)
        }
        Text("MPG method: ${methodLabel(state.mpgMethod)}", fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = state.methodOverride == null,
                onClick = { vm.setMethodOverride(null) }, label = { Text("Auto") })
            FilterChip(selected = state.methodOverride == MpgMethod.FUEL_RATE,
                onClick = { vm.setMethodOverride(MpgMethod.FUEL_RATE) }, label = { Text("Fuel-rate") })
            FilterChip(selected = state.methodOverride == MpgMethod.MAF,
                onClick = { vm.setMethodOverride(MpgMethod.MAF) }, label = { Text("MAF") })
            FilterChip(selected = state.methodOverride == MpgMethod.SPEED_DENSITY,
                onClick = { vm.setMethodOverride(MpgMethod.SPEED_DENSITY) }, label = { Text("Speed-density") })
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Text("Units", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = state.distanceUnit == "MPH",
                onClick = { vm.setDistanceUnit("MPH") }, label = { Text("mph") })
            FilterChip(selected = state.distanceUnit == "KMH",
                onClick = { vm.setDistanceUnit("KMH") }, label = { Text("km/h") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = state.economyUnit == "MPG_US",
                onClick = { vm.setEconomyUnit("MPG_US") }, label = { Text("MPG US") })
            FilterChip(selected = state.economyUnit == "MPG_IMP",
                onClick = { vm.setEconomyUnit("MPG_IMP") }, label = { Text("MPG imp") })
            FilterChip(selected = state.economyUnit == "L_PER_100KM",
                onClick = { vm.setEconomyUnit("L_PER_100KM") }, label = { Text("L/100km") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = state.tempUnit == "C",
                onClick = { vm.setTempUnit("C") }, label = { Text("°C") })
            FilterChip(selected = state.tempUnit == "F",
                onClick = { vm.setTempUnit("F") }, label = { Text("°F") })
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Text("Alerts", fontWeight = FontWeight.SemiBold)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Enable driver alerts")
            Switch(checked = state.alertsEnabled, onCheckedChange = { vm.setAlertsEnabled(it) })
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("High coolant: ${state.coolantMaxF.toInt()} °F")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.setCoolantMaxF(state.coolantMaxF - 5) }) { Text("−") }
                OutlinedButton(onClick = { vm.setCoolantMaxF(state.coolantMaxF + 5) }) { Text("+") }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Low fuel: ${state.lowFuelPct.toInt()} %")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.setLowFuelPct(state.lowFuelPct - 1) }) { Text("−") }
                OutlinedButton(onClick = { vm.setLowFuelPct(state.lowFuelPct + 1) }) { Text("+") }
            }
        }
        Text("Also alerts on new trouble codes and low battery voltage (<13.2V running).",
            fontSize = 12.sp)

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        if (state.mpgMethod == MpgMethod.SPEED_DENSITY) {
            Text("Fuel model calibration (speed-density)", fontWeight = FontWeight.SemiBold)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Displacement: ${"%.1f".format(state.displacementL)} L")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.setDisplacement(state.displacementL - 0.1) }) { Text("−") }
                    OutlinedButton(onClick = { vm.setDisplacement(state.displacementL + 0.1) }) { Text("+") }
                }
            }
            Text("Volumetric efficiency (VE): ${"%.2f".format(state.ve)}")
            Slider(
                value = state.ve.toFloat(),
                onValueChange = { vm.setVe(it.toDouble()) },
                valueRange = 0.5f..1.1f,
            )
            Text(
                "No-MAF engines estimate fuel by speed-density. Set displacement, then " +
                    "after a full tank set VE = 0.85 × (logged gal ÷ pump gal).",
                fontSize = 12.sp
            )
        } else {
            Text("Fuel model: ${methodLabel(state.mpgMethod)} — no calibration needed.",
                fontSize = 12.sp)
        }
    }
}

@Composable
private fun DriveScreen(vm: MainViewModel, state: UiState) {
    val r = state.readings
    val sup: (Int) -> Boolean = { state.supportedPids.isEmpty() || it in state.supportedPids }
    val avgMpgUs = if (state.tripGallons > 1e-6) state.tripMiles / state.tripGallons else 0.0
    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Trip average", color = MaterialTheme.colorScheme.secondary)
        Text(formatEconomy(avgMpgUs, state.economyUnit), fontSize = 44.sp, fontWeight = FontWeight.Bold)
        Text("Instant: ${formatEconomy(r.instMpg, state.economyUnit)}", fontSize = 18.sp)
        Text("${methodLabel(state.mpgMethod)} · ${state.vehicleLabel}",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)

        val dist = if (state.distanceUnit == "KMH")
            "%.2f km".format(state.tripMiles * ObdMath.KPH_PER_MPH)
        else "%.2f mi".format(state.tripMiles)
        val fuel = if (state.economyUnit == "L_PER_100KM")
            "%.2f L".format(state.tripGallons * ObdMath.L_PER_GAL)
        else "%.3f gal".format(state.tripGallons)
        Text("trip $dist · $fuel · ${state.tripElapsedSec}s")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!state.tripActive) {
                Button(onClick = { vm.startTrip() }, enabled = state.connected) { Text("Start trip") }
            } else {
                Button(onClick = { vm.stopTrip() }) { Text("Stop & save trip") }
            }
        }

        HorizontalDivider()
        Text("Live data", fontWeight = FontWeight.SemiBold)
        if (state.mpgMethod == MpgMethod.NONE && state.connected) {
            Text("No MPG source on this vehicle (no fuel-rate, MAF, or MAP PID).",
                color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        }
        if (sup(0x0D)) Gauge("Speed", formatSpeed(r.mph, state.distanceUnit))
        if (sup(0x0C)) Gauge("RPM", "%.0f".format(r.rpm))
        if (sup(0x10)) r.mafGps?.let { Gauge("MAF", "%.1f g/s".format(it)) }
        if (sup(0x5E)) r.fuelRateLph?.let { Gauge("Fuel rate", "%.1f L/h".format(it)) }
        if (sup(0x0B)) Gauge("MAP", "%.0f kPa".format(r.mapKpa))
        if (sup(0x0F)) Gauge("Intake air", formatTemp(r.iatC, state.tempUnit))
        if (sup(0x05)) r.coolantC?.let { Gauge("Coolant", formatTemp(it, state.tempUnit)) }
        if (sup(0x04)) r.loadPct?.let { Gauge("Engine load", "%.0f %%".format(it)) }
        if (sup(0x11)) r.throttlePct?.let { Gauge("Throttle", "%.0f %%".format(it)) }
        if (sup(0x2F)) r.fuelLevelPct?.let { Gauge("Fuel level", "%.0f %%".format(it)) }
        if (sup(0x42)) r.volts?.let { Gauge("Battery", "%.1f V".format(it)) }
        if (sup(0x44)) Gauge("Lambda", "%.3f".format(r.lambda))

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

private fun formatEconomy(mpgUs: Double, unit: String): String = when (unit) {
    "MPG_IMP" -> "%.1f MPG".format(ObdMath.mpgUsToImperial(mpgUs))
    "L_PER_100KM" -> "%.1f L/100km".format(ObdMath.mpgUsToLper100km(mpgUs))
    else -> "%.1f MPG".format(mpgUs)
}

private fun formatSpeed(mph: Double, unit: String): String =
    if (unit == "KMH") "%.0f km/h".format(ObdMath.mphToKph(mph)) else "%.0f mph".format(mph)

private fun formatTemp(celsius: Double, unit: String): String =
    if (unit == "F") "%.0f °F".format(celsius * 9.0 / 5.0 + 32.0) else "%.0f °C".format(celsius)

private fun methodLabel(m: MpgMethod): String = when (m) {
    MpgMethod.FUEL_RATE -> "fuel-rate PID"
    MpgMethod.MAF -> "MAF"
    MpgMethod.SPEED_DENSITY -> "speed-density"
    MpgMethod.NONE -> "no MPG source"
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
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Trouble codes", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.readDtcs() }, enabled = state.connected) { Text("Read codes") }
            OutlinedButton(onClick = { vm.clearDtcs() }, enabled = state.connected) { Text("Clear codes") }
        }
        if (state.dtcMessage.isNotEmpty()) Text(state.dtcMessage)
        state.dtcs.forEach { code ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(code, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    DtcDescriptions.describe(code)?.let { Text(it, fontSize = 13.sp) }
                }
            }
        }
        state.freezeDtc?.let {
            HorizontalDivider()
            Text("Freeze frame", fontWeight = FontWeight.SemiBold)
            Text("Snapshot captured for ${DtcDescriptions.label(it)}", fontSize = 13.sp)
        }
        state.readiness?.let { rd ->
            HorizontalDivider()
            Text("Emissions readiness", fontWeight = FontWeight.SemiBold)
            Text("MIL lamp: ${if (rd.milOn) "ON" else "off"} · ${rd.dtcCount} stored code(s)",
                fontSize = 12.sp)
            rd.monitors.forEach { m ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(m.name)
                    Text(if (m.complete) "Ready" else "Not ready",
                        fontWeight = FontWeight.Bold,
                        color = if (m.complete) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.error)
                }
            }
        }
        if (!state.connected) Text("Connect first.", color = MaterialTheme.colorScheme.error)
        Text("Clearing turns the light off but does not fix the fault — real codes return.",
            fontSize = 12.sp)
    }
}

@Composable
private fun LiveScreen(vm: MainViewModel, state: UiState) {
    val supported = state.supportedPids.filter { it in PidRegistry.byPid }.sorted()
    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text("Live data", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (!state.connected)
                Text("Connect on the Connect tab first.", color = MaterialTheme.colorScheme.error)
            else
                Text("${supported.size} supported PIDs", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.secondary)
        }
        items(supported) { pid ->
            val spec = PidRegistry.byPid[pid]
            if (spec != null) {
                val v = state.livePids[pid]
                Gauge(spec.name, if (v == null) "…" else formatPidValue(spec, v, state))
            }
        }
    }
}

private fun formatPidValue(spec: PidSpec, value: Double, state: UiState): String = when {
    spec.pid in PidRegistry.tempPids -> formatTemp(value, state.tempUnit)
    spec.pid == 0x0D -> formatSpeed(value / ObdMath.KPH_PER_MPH, state.distanceUnit)
    spec.unit.isEmpty() -> "%.3f".format(value)
    else -> "%.1f %s".format(value, spec.unit)
}
