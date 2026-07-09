package com.truempg.app

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.FileProvider
import com.truempg.app.data.Trip
import com.truempg.app.obd.Diagnostics
import com.truempg.app.obd.DtcDescriptions
import com.truempg.app.obd.ObdMath
import com.truempg.app.obd.ObdMath.MpgMethod
import com.truempg.app.obd.PidRegistry
import com.truempg.app.obd.PidSpec
import com.truempg.app.ui.TrueMpgTheme
import java.io.File
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
    var showHud by remember { mutableStateOf(false) }

    if (showHud) {
        HudScreen(state) { showHud = false }
        return
    }

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
                1 -> DriveScreen(vm, state) { showHud = true }
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
        var showHelp by remember { mutableStateOf(false) }
        TextButton(onClick = { showHelp = !showHelp }) {
            Text(if (showHelp) "Hide auto-connect help" else "Auto-connect not firing after truck sits?")
        }
        if (showHelp) {
            Text("If the truck is off for a while, aggressive phones kill the app so it " +
                "can't wake when the adapter powers back up. To make it reliable:",
                fontSize = 12.sp)
            Text("1. Tap \"Allow background running\" above (exempt from battery optimization).",
                fontSize = 12.sp)
            Text("2. Enable Autostart / auto-launch for TrueMPG — off by default on Xiaomi/MIUI, " +
                "Oppo, Vivo, Huawei, OnePlus.", fontSize = 12.sp)
            Text("3. Open recent apps and lock TrueMPG so it isn't swiped away.", fontSize = 12.sp)
            OutlinedButton(onClick = { openAutostartSettings(ctx) }) { Text("Open Autostart settings") }
        }

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
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Keep screen on while driving")
            Switch(checked = state.keepScreenOn, onCheckedChange = { vm.setKeepScreenOn(it) })
        }
        if (state.knownVehicles.isNotEmpty())
            Text("Known vehicles: ${state.knownVehicles.joinToString(", ")} " +
                "(auto-switches by VIN on connect)", fontSize = 12.sp)

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

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        Text("Calibration & fuel", fontWeight = FontWeight.SemiBold)
        Text("Active profile: ${state.activeCalName} · factor ${"%.3f".format(state.activeFactor)}",
            fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            state.calProfiles.forEach { name ->
                FilterChip(selected = name == state.activeCalName,
                    onClick = { vm.setCalProfile(name) }, label = { Text(name) })
            }
        }
        var newProfile by remember { mutableStateOf("") }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = newProfile, onValueChange = { newProfile = it },
                label = { Text("New profile (e.g. Towing)") },
                singleLine = true, modifier = Modifier.weight(1f)
            )
            Button(
                onClick = { vm.addCalProfile(newProfile.trim()); newProfile = "" },
                enabled = state.connected && newProfile.isNotBlank()
            ) { Text("Add") }
        }

        Text("Since last fill-up: ${"%.1f".format(state.milesSinceFillup)} mi · " +
            "${"%.2f".format(state.galSinceFillup)} gal used (est)", fontSize = 12.sp)
        var fillGal by remember { mutableStateOf("") }
        var fillPrice by remember { mutableStateOf("") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = fillGal, onValueChange = { fillGal = it },
                label = { Text("Pump gallons") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = fillPrice, onValueChange = { fillPrice = it },
                label = { Text("Price /gal") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
        }
        Button(
            onClick = {
                val g = fillGal.toDoubleOrNull()
                val p = fillPrice.toDoubleOrNull() ?: 0.0
                if (g != null && g > 0) { vm.logFillup(g, p); fillGal = ""; fillPrice = "" }
            },
            enabled = state.connected && state.galSinceFillup > 0.05
        ) { Text("Log fill-up & calibrate") }
        Text("Enter what the pump showed. The app tunes the active profile so estimated " +
            "MPG matches your real MPG, and tracks fuel cost per trip and per month.",
            fontSize = 12.sp)
    }
}

@Composable
private fun DriveScreen(vm: MainViewModel, state: UiState, onOpenHud: () -> Unit) {
    val r = state.readings
    val sup: (Int) -> Boolean = { state.supportedPids.isEmpty() || it in state.supportedPids }
    val avgMpgUs = if (state.tripGallons > 1e-6) state.tripMiles / state.tripGallons else 0.0

    // Keep the screen awake while on the Drive tab (dash mounting).
    val view = LocalView.current
    DisposableEffect(state.keepScreenOn) {
        view.keepScreenOn = state.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Trip average", color = MaterialTheme.colorScheme.secondary)
        Text(formatEconomy(avgMpgUs, state.economyUnit), fontSize = 56.sp, fontWeight = FontWeight.Bold)
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
        if (state.isTurbo)
            Gauge("Boost", "%.1f psi (peak %.1f)".format(state.boostPsi, state.peakBoostPsi))

        HorizontalDivider()
        Text("Driving coach", fontWeight = FontWeight.SemiBold)
        Text("Trip score: ${state.coachScore}/100", fontSize = 20.sp,
            color = MaterialTheme.colorScheme.secondary)
        Text("${state.coachHardAccels} hard accels · idle ${"%.0f".format(state.coachIdlePct)}% · " +
            "high load ${"%.0f".format(state.coachHighLoadPct)}%", fontSize = 12.sp)
        if (state.coachTip.isNotEmpty()) Text(state.coachTip, fontSize = 12.sp)

        HorizontalDivider()
        Text("Performance", fontWeight = FontWeight.SemiBold)
        Text("Best 0-60 mph: ${if (state.best060Sec > 0) "%.1f s".format(state.best060Sec) else "--"}",
            fontSize = 13.sp)
        Text("Best 1/4 mile: ${if (state.bestQuarterSec > 0) "%.1f s @ %.0f mph".format(state.bestQuarterSec, state.bestQuarterMph) else "--"}",
            fontSize = 13.sp)
        Text("Timers arm automatically from a stop; OBD speed is ~1s resolution so times are approximate.",
            fontSize = 11.sp)

        Spacer(Modifier.height(4.dp))
        Button(onClick = onOpenHud, enabled = state.connected) { Text("HUD mode") }

        if (!state.connected) Text("Connect on the Connect tab first.",
            color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun HudScreen(state: UiState, onExit: () -> Unit) {
    val avgMpgUs = if (state.tripGallons > 1e-6) state.tripMiles / state.tripGallons else 0.0
    Box(
        Modifier.fillMaxSize().background(Color.Black).clickable { onExit() },
        contentAlignment = Alignment.Center
    ) {
        // Mirror horizontally so it reads correctly reflected in the windshield.
        Column(
            Modifier.graphicsLayer { scaleX = -1f },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(formatSpeed(state.readings.mph, state.distanceUnit),
                color = Color(0xFF39FF14), fontSize = 96.sp, fontWeight = FontWeight.Bold)
            Text(formatEconomy(state.readings.instMpg, state.economyUnit),
                color = Color(0xFF39FF14), fontSize = 44.sp, fontWeight = FontWeight.Bold)
            if (state.isTurbo)
                Text("${"%.1f".format(state.boostPsi)} psi",
                    color = Color(0xFF39FF14), fontSize = 36.sp, fontWeight = FontWeight.Bold)
            Text("tap to exit", color = Color(0xFF2A8F0E), fontSize = 14.sp)
        }
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

// Known OEM autostart / background-launch manager screens (best effort).
private val AUTOSTART_TARGETS = listOf(
    "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
    "com.letv.android.letvsafe" to "com.letv.android.letvsafe.AutostartManageActivity",
    "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
    "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
    "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
    "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
    "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
    "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
    "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
    "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
    "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
)

private fun openAutostartSettings(context: Context) {
    for ((pkg, cls) in AUTOSTART_TARGETS) {
        try {
            context.startActivity(
                Intent().setComponent(ComponentName(pkg, cls))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        } catch (e: Exception) { /* not this OEM; try the next */ }
    }
    // Fallback: this app's system settings page.
    try {
        context.startActivity(
            Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + context.packageName)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {}
}

private fun shareCsv(context: Context, file: File) {
    try {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(
            Intent.createChooser(send, "Share ${file.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) { /* nothing to share / no handler */ }
}

private fun blackboxFile(context: Context, name: String): File =
    File(File(context.filesDir, "blackbox"), name)

private fun methodLabel(m: MpgMethod): String = when (m) {
    MpgMethod.FUEL_RATE -> "fuel-rate PID"
    MpgMethod.MAF -> "MAF"
    MpgMethod.SPEED_DENSITY -> "speed-density"
    MpgMethod.NONE -> "no MPG source"
}

@Composable
private fun TripsScreen(vm: MainViewModel, state: UiState) {
    val fmt = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
    val price = state.lastPricePerGal
    val ctx = LocalContext.current
    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Trips & fuel", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                if (state.trips.isNotEmpty())
                    TextButton(onClick = { vm.clearTripHistory() }) { Text("Clear") }
            }
            if (state.monthlyFuelCost > 0)
                Text("This month: $${"%.2f".format(state.monthlyFuelCost)} on fuel",
                    color = MaterialTheme.colorScheme.secondary)
        }

        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Black-box logging", fontWeight = FontWeight.SemiBold)
                Switch(checked = state.blackBoxEnabled,
                    onCheckedChange = { vm.setBlackBoxEnabled(it) })
            }
            Text("Records every trip to CSV (all PIDs) for later review/export.", fontSize = 12.sp)
        }

        if (state.batteryReadings.isNotEmpty()) {
            item {
                HorizontalDivider()
                Text("Battery health", fontWeight = FontWeight.SemiBold)
                state.batteryAvg?.let {
                    Text("Recent avg start voltage: ${"%.1f".format(it)} V" +
                        (if (it < 12.0) " — trending low" else ""),
                        color = if (it < 12.0) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.secondary, fontSize = 12.sp)
                }
                state.batteryReadings.take(6).forEach { br ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(fmt.format(Date(br.timestamp)), fontSize = 12.sp)
                        Text("${"%.1f".format(br.voltage)} V", fontSize = 12.sp,
                            fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (state.maintenance.isNotEmpty()) {
            item {
                HorizontalDivider()
                Text("Maintenance", fontWeight = FontWeight.SemiBold)
                Text("Odometer (app-tracked): ${"%.0f".format(state.odometer)} mi", fontSize = 12.sp)
                var odoText by remember { mutableStateOf("") }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = odoText, onValueChange = { odoText = it },
                        label = { Text("Set odometer (mi)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = { odoText.toDoubleOrNull()?.let { vm.setOdometer(it); odoText = "" } },
                        enabled = state.connected) { Text("Set") }
                }
            }
            items(state.maintenance) { m ->
                val remaining = m.remaining(state.odometer)
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(m.name, fontWeight = FontWeight.Bold)
                            Text(
                                if (remaining <= 0) "DUE — ${"%.0f".format(-remaining)} mi over"
                                else "in ${"%.0f".format(remaining)} mi (every ${m.intervalMiles})",
                                fontSize = 12.sp,
                                color = if (remaining <= 0) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.secondary
                            )
                        }
                        TextButton(onClick = { vm.markServiceDone(m.name) }, enabled = state.connected) {
                            Text("Done")
                        }
                    }
                }
            }
            item {
                var svcName by remember { mutableStateOf("") }
                var svcInterval by remember { mutableStateOf("") }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = svcName, onValueChange = { svcName = it },
                            label = { Text("Service item") }, singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = svcInterval, onValueChange = { svcInterval = it },
                            label = { Text("Every mi") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Button(
                        onClick = {
                            val iv = svcInterval.toIntOrNull()
                            if (svcName.isNotBlank() && iv != null && iv > 0) {
                                vm.addServiceItem(svcName.trim(), iv); svcName = ""; svcInterval = ""
                            }
                        },
                        enabled = state.connected
                    ) { Text("Add service item") }
                }
            }
        }

        if (state.fillups.isNotEmpty()) {
            item { HorizontalDivider(); Text("Recent fill-ups", fontWeight = FontWeight.SemiBold) }
            items(state.fillups.take(5)) { f ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${"%.1f".format(f.actualMpg)} MPG actual · " +
                            "${"%.2f".format(f.gallons)} gal", fontWeight = FontWeight.Bold)
                        Text("${fmt.format(Date(f.timestamp))}" +
                            (if (f.pricePerGal > 0)
                                " · $${"%.2f".format(f.cost)} @ $${"%.2f".format(f.pricePerGal)}/gal"
                            else ""), fontSize = 12.sp)
                    }
                }
            }
        }

        item { HorizontalDivider(); Text("Trip history", fontWeight = FontWeight.SemiBold) }
        if (state.trips.isEmpty()) {
            item { Text("No saved trips yet. Start one on the Drive tab.") }
        } else {
            items(state.trips) { t ->
                val logName = "trip_${t.startedAt}.csv".takeIf { state.logNames.contains(it) }
                TripRow(t, fmt.format(Date(t.startedAt)), price, logName, ctx)
            }
        }
    }
}

@Composable
private fun TripRow(t: Trip, dateLabel: String, pricePerGal: Double, logName: String?, ctx: Context) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(dateLabel, fontWeight = FontWeight.Bold)
                if (logName != null)
                    TextButton(onClick = { shareCsv(ctx, blackboxFile(ctx, logName)) }) { Text("Export CSV") }
            }
            Text("${"%.1f".format(t.avgMpg)} MPG avg", fontSize = 20.sp,
                color = MaterialTheme.colorScheme.secondary)
            Text("${"%.2f".format(t.miles)} mi · ${"%.3f".format(t.gallons)} gal · " +
                "${t.durationSec / 60}m ${t.durationSec % 60}s", fontSize = 12.sp)
            if (t.score > 0)
                Text("Score ${t.score}/100 · ${t.hardAccels} hard accels · idle ${"%.0f".format(t.idlePct)}%" +
                    (if (t.peakBoostPsi > 0.5) " · peak ${"%.1f".format(t.peakBoostPsi)} psi" else ""),
                    fontSize = 12.sp)
            if (pricePerGal > 0) {
                val cost = t.gallons * pricePerGal
                val perMile = if (t.miles > 0.01) cost / t.miles else 0.0
                Text("$${"%.2f".format(cost)} · $${"%.3f".format(perMile)}/mi", fontSize = 12.sp)
            }
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
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(code, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        TextButton(onClick = { vm.analyzeDtc(code) }, enabled = state.connected) {
                            Text("Analyze")
                        }
                    }
                    DtcDescriptions.describe(code)?.let { Text(it, fontSize = 13.sp) }
                }
            }
        }
        if (state.diagnosing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Analyzing…")
            }
        }
        state.diagnosis?.let { dx -> DiagnosisCard(dx) }
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

        HorizontalDivider()
        Text("Experimental: Ford enhanced PIDs (mode 22)", fontWeight = FontWeight.SemiBold)
        var enhPid by remember { mutableStateOf("") }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = enhPid, onValueChange = { enhPid = it },
                label = { Text("PID (hex, e.g. 1E1C)") }, singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Button(onClick = { vm.queryEnhanced(enhPid) }, enabled = state.connected) { Text("Read") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("1E1C" to "Trans temp?", "1154" to "Oil temp?", "1638" to "Oil life?")
                .forEach { (pid, label) ->
                    AssistChip(onClick = { vm.queryEnhanced(pid) }, label = { Text(label) })
                }
        }
        if (state.enhancedResult.isNotEmpty()) Text(state.enhancedResult, fontSize = 12.sp)
        Text("EXPERIMENTAL and UNVERIFIED: Ford mode-22 PIDs aren't publicly standardized. " +
            "Values may be wrong or blank — many live on MS-CAN (gateway-blocked on your truck). " +
            "Raw bytes are shown so you can decode against FORScan references. Preset PIDs are guesses.",
            fontSize = 11.sp)
    }
}

@Composable
private fun DiagnosisCard(dx: Diagnostics.Diagnosis) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Diagnosis: ${dx.code}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(dx.title, color = MaterialTheme.colorScheme.secondary)
            if (dx.evidence.isNotEmpty()) {
                Text("Evidence (live snapshot)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                dx.evidence.forEach { Text("• $it", fontSize = 12.sp) }
            }
            Text("Likely causes", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            dx.causes.forEach { c ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(c.text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        c.confidence.label(), fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        color = when (c.confidence) {
                            Diagnostics.Confidence.LIKELY -> MaterialTheme.colorScheme.error
                            Diagnostics.Confidence.POSSIBLE -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
            Text("Checks to confirm", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            dx.checks.forEach { Text("• $it", fontSize = 12.sp) }
            Text("Confidence is from a live snapshot — drive under the fault's conditions and " +
                "re-analyze for a stronger read. Never replace parts on \"Possible\" alone.",
                fontSize = 11.sp)
        }
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
