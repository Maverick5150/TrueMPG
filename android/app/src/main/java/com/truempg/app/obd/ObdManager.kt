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

    /** HS-CAN init: echo/linefeed/spaces off, headers off, protocol 6. */
    private suspend fun initAdapter() {
        for (cmd in listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP6")) {
            send(cmd, settleMs = if (cmd == "ATZ") 900 else 200)
        }
    }

    /** Send one command, read until the '>' prompt (or timeout). */
    suspend fun send(cmd: String, settleMs: Long = 0, timeoutMs: Long = 2500): String =
        withContext(Dispatchers.IO) {
            val out = output ?: return@withContext ""
            val inp = input ?: return@withContext ""
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
