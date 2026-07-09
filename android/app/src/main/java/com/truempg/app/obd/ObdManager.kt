package com.truempg.app.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Bluetooth Classic (SPP / RFCOMM) link to the OBDLink MX+, plus the ELM/STN
 * command I/O and HS-CAN init we proved on the desktop.
 *
 * Caller MUST hold BLUETOOTH_CONNECT (API 31+) before connecting.
 */
@SuppressLint("MissingPermission")
class ObdManager(context: Context) {

    private val appContext = context.applicationContext

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /** True if BLUETOOTH_SCAN is held (always true below Android 12). */
    private fun hasScanPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            appContext.checkSelfPermission(
                android.Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    // Serializes all command I/O so the poll loop and on-demand commands
    // (DTC read/clear) never interleave bytes on the shared serial link.
    private val ioMutex = Mutex()

    val isConnected: Boolean get() = socket?.isConnected == true

    /** Bonded devices that look like an OBD adapter (falls back to all). */
    fun pairedObdDevices(): List<BluetoothDevice> {
        val bonded = try {
            adapter?.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
        val obd = bonded.filter {
            val n = try { (it.name ?: "").uppercase() } catch (e: SecurityException) { "" }
            n.contains("OBD") || n.contains("MX") || n.contains("LINK") ||
                n.contains("ELM") || n.contains("VLINK") || n.contains("VIECAR")
        }
        return if (obd.isNotEmpty()) obd else bonded
    }

    fun bluetoothSupported() = adapter != null
    fun bluetoothEnabled() = adapter?.isEnabled == true

    /** Resolve a paired device by MAC address (for auto-connect, no scan). */
    fun deviceFor(address: String): BluetoothDevice? =
        try { adapter?.getRemoteDevice(address) } catch (e: Exception) { null }

    private val SPP_UUID: UUID =
        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    suspend fun connect(device: BluetoothDevice): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            close()
            // cancelDiscovery() needs BLUETOOTH_SCAN on Android 12+. We connect
            // to an already-paired device by address, so this is only an
            // optimization -- skip it (or swallow) if the permission is absent.
            if (hasScanPermission()) {
                try { adapter?.cancelDiscovery() } catch (_: SecurityException) {}
            }
            val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
            s.connect()
            socket = s
            input = s.inputStream
            output = s.outputStream
            initAdapter()
            Result.success(Unit)
        } catch (e: SecurityException) {
            close()
            Result.failure(
                SecurityException("Bluetooth permission not granted. " +
                    "Enable it for TrueMPG in Settings, then reconnect.")
            )
        } catch (e: Exception) {
            close()
            Result.failure(e)
        }
    }

    /** Saved protocol number to try first (fast reconnect); may be null/blank. */
    var preferredProtocol: String? = null

    /** The protocol that actually returned PID data on this connection. */
    var workingProtocol: String? = null
        private set

    // CAN first (modern vehicles), then legacy; "0" (auto) only as a last resort.
    private val protocolCandidates = listOf("6", "7", "8", "9", "1", "2", "3", "4", "5", "0")

    /**
     * Init the adapter, then find a protocol that ACTUALLY returns PID data.
     * Tries the saved protocol first, then each candidate, verifying with a real
     * 0100 query before accepting. Fixes ATSP0 auto-detect connecting but
     * returning empty on some vehicles (e.g. Ford HS-CAN needs protocol 6).
     */
    private suspend fun initAdapter() {
        send("ATZ", settleMs = 900)
        for (cmd in listOf("ATE0", "ATL0", "ATS0", "ATH0")) send(cmd, settleMs = 150)
        workingProtocol = negotiateProtocol()
    }

    private suspend fun negotiateProtocol(): String? {
        val order = ArrayList<String>()
        preferredProtocol?.takeIf { it.isNotBlank() }?.let { order.add(it) }
        for (p in protocolCandidates) if (p !in order) order.add(p)
        for (p in order) {
            send("ATSP$p", settleMs = 150)
            // First probe may return SEARCHING; try up to twice before rejecting.
            repeat(2) {
                val raw = send("0100", settleMs = 200, timeoutMs = 4000)
                if (ObdMath.parseData(raw, 0x01, 0x00) != null) {
                    return if (p == "0") (detectedProtocol() ?: "0") else p
                }
            }
        }
        return null
    }

    /** Read the negotiated protocol number via ATDPN (drops the 'A' auto flag). */
    suspend fun detectedProtocol(): String? {
        val raw = send("ATDPN").uppercase()
            .replace(">", "").replace("\r", "").replace("\n", "").trim()
        val cleaned = raw.removePrefix("A").trim()
        return cleaned.lastOrNull()?.takeIf { it.isLetterOrDigit() }?.toString()
    }

    /** Scan mode-01 supported-PID bitmasks (00/20/40/60/80) into a PID set. */
    suspend fun querySupportedPids(): Set<Int> {
        val supported = HashSet<Int>()
        for (base in listOf(0x00, 0x20, 0x40, 0x60, 0x80)) {
            val data = queryPid(base) ?: break
            val decoded = ObdMath.decodeSupportedPids(base, data)
            supported.addAll(decoded)
            if ((base + 0x20) !in decoded) break
        }
        if (supported.isEmpty()) {
            // Bitmask unavailable -> probe the PIDs we actually use directly, so
            // a flaky 0100 can't leave us thinking the vehicle supports nothing.
            for (pid in listOf(0x04, 0x05, 0x0B, 0x0C, 0x0D, 0x0F, 0x10, 0x11, 0x2F, 0x42, 0x44, 0x5E)) {
                if (queryPid(pid) != null) supported.add(pid)
            }
        }
        return supported
    }

    /** Mode 09 PID 02 VIN, or null if the vehicle/adapter won't report it. */
    suspend fun readVin(): String? = ObdMath.parseVin(send("0902", timeoutMs = 3500))

    /** Mode 01 PID 51 fuel-type code (1=gasoline, 4=diesel), or null. */
    suspend fun queryFuelType(): Int? = ObdMath.fuelType(queryPid(0x51) ?: emptyList())

    /**
     * Send one command, read until the '>' prompt (or timeout). Serialized via
     * ioMutex and prefixed with an input-buffer flush so a previous response's
     * leftover bytes can't corrupt this command's reply (this is what broke
     * mode-03 DTC reads once polling ran concurrently).
     */
    suspend fun send(cmd: String, settleMs: Long = 0, timeoutMs: Long = 2500): String =
        withContext(Dispatchers.IO) {
            ioMutex.withLock {
                val out = output ?: return@withLock ""
                val inp = input ?: return@withLock ""
                // discard any stale bytes still sitting in the buffer
                try {
                    val drain = ByteArray(256)
                    while (inp.available() > 0) inp.read(drain)
                } catch (_: Exception) {}
                out.write((cmd + "\r").toByteArray())
                out.flush()
                if (settleMs > 0) Thread.sleep(settleMs)
                val sb = StringBuilder()
                val deadline = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(256)
                while (System.currentTimeMillis() < deadline) {
                    val avail = inp.available()
                    if (avail > 0) {
                        val n = inp.read(buf, 0, minOf(avail, buf.size))
                        if (n > 0) {
                            sb.append(String(buf, 0, n, Charsets.US_ASCII))
                            if (sb.contains('>')) break
                        }
                    } else {
                        Thread.sleep(15)
                    }
                }
                sb.toString()
            }
        }

    /** Query a mode-01 PID and return decoded data bytes (or null). */
    suspend fun queryPid(pid: Int): List<Int>? {
        val raw = send("01%02X".format(pid))
        return ObdMath.parseData(raw, 0x01, pid)
    }

    suspend fun readDtcs(): List<String> = ObdMath.decodeDtcs(send("03"))

    /** Clear all DTCs (mode 04). Returns true if the adapter acked ("44"). */
    suspend fun clearDtcs(): Boolean = send("04").uppercase().replace(" ", "").contains("44")

    fun close() {
        try { input?.close() } catch (_: Exception) {}
        try { output?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        input = null; output = null; socket = null
    }
}
