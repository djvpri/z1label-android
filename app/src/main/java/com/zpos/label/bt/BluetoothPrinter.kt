package com.zpos.label.bt

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.Method
import java.util.UUID

/**
 * Kirim bytes ESC/POS ke printer label Bluetooth Classic (SPP).
 * Android 12+ butuh BLUETOOTH_CONNECT runtime permission (di MainActivity).
 *
 * Pola mengikuti aplikasi print-label GPrinter:
 *  - UUID SPP standar (00001101-...) + fallback refleksi createRfcommSocket(1)
 *  - cancelDiscovery() sebelum connect() (Bluetooth tak bisa connect saat discovery)
 *  - Socket disimpan & dijaga hidup (tak buka-tutup per cetak) -> cetak berikutnya cepat
 *  - autoConnect(): connect di background utk perangkat tersimpan (bisa saat app buka)
 *  - Thread pemantau InputStream: deteksi putus -> callback (status UI + siap reconnect)
 */
object BluetoothPrinter {

    private val SPP_UUID: UUID =
        UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var monitor: Thread? = null
    @Volatile private var running = false
    private val monitorLock = Any()

    /** Callback status koneksi; `true` = tersambung (socket hidup), `false` = putus. */
    var onStateChange: ((connected: Boolean) -> Unit)? = null
    @Volatile private var notifiedConnected = false

    // device hasil discovery (di luar daftar bonded)
    private val discovered = LinkedHashMap<String, BluetoothDevice>()
    private var scanReceiver: BroadcastReceiver? = null
    fun discoveredDevices(): List<BluetoothDevice> = discovered.values.toList()

    /** Mulai discovery Bluetooth; setiap device -> onUpdate() (refresh UI). */
    fun startScan(context: Context, onUpdate: () -> Unit) {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        if (scanReceiver != null) return // sudah jalan
        discovered.clear()
        scanReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action != BluetoothDevice.ACTION_FOUND) return
                val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                discovered[d.address] = d
                onUpdate()
            }
        }
        ContextCompat.registerReceiver(
            context, scanReceiver,
            IntentFilter(BluetoothDevice.ACTION_FOUND),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        adapter.startDiscovery()
    }

    fun stopScan(context: Context) {
        scanReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        scanReceiver = null
        runCatching { BluetoothAdapter.getDefaultAdapter()?.cancelDiscovery() }
    }

    fun pairedDevices(): List<BluetoothDevice> {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
        return adapter.bondedDevices?.toList() ?: emptyList()
    }

    /** Connect pakai UUID SPP > fallback refleksi. Hanya connect (tanpa close) + mulai monitor. */
    fun connect(address: String): String? {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return "Bluetooth tidak tersedia"
            val dev = adapter.getRemoteDevice(address) ?: return "Perangkat tidak ditemukan"

            var s = try {
                dev.createRfcommSocketToServiceRecord(SPP_UUID)
            } catch (_: Exception) { null }

            if (s == null) {
                // fallback refleksi (beberapa printer tak daftarkan UUID SPP)
                val m: Method = dev.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                s = m.invoke(dev, 1) as BluetoothSocket
            }

            adapter.cancelDiscovery()   // kunci: connect gagal/terlambat saat discovery
            s.connect()

            socket = s
            out = s.outputStream
            startMonitor(s)
            notifyConnected(true)
            return null
        } catch (e: Exception) {
            close()
            return e.message ?: "Gagal konek"
        }
    }

    /**
     * Connect di background utk perangkat tersimpan. Tak `cancel()` UI & tak login block.
     * Hasil lewat [onStateChange]. Kalau sudah terhubung (dari connect sebelumnya) -> sukses cepat.
     */
    fun autoConnect(address: String?) {
        if (address.isNullOrBlank()) return
        Thread {
            if (connected()) { notifyConnected(true); return@Thread }
            val err = connect(address)
            if (err != null) {
                notifyConnected(false)
                // siap utk usaha ulang berikutnya (user tekan cetak / buka lagi)
            }
        }.apply { isDaemon = true }.start()
    }

    /** Sudah punya socket hidup? */
    fun connected(): Boolean = socket?.isConnected == true

    fun write(bytes: ByteArray): String? {
        val o = out ?: return "Printer belum konek"
        return try {
            o.write(bytes)
            o.flush()
            null
        } catch (e: Exception) {
            notifyConnected(false)  // stream putus -> status
            close()
            e.message ?: "Gagal kirim"
        }
    }

    /** Tutup socket & hentikan monitor. */
    fun close() {
        synchronized(monitorLock) { running = false }
        monitor?.let { runCatching { it.interrupt() } }
        monitor = null
        try { out?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        out = null
        socket = null
        notifyConnected(false)
    }

    /**
     * Pantau InputStream: begitu EOF/error (printer mati / kabel putus / jauh), otomatis tutup
     * & tandai terputus. Tidak auto-retry loop (boros baterai) — panggil autoConnect lagi
     * dari UI bila mau reconnect.
     */
    private fun startMonitor(s: BluetoothSocket) {
        synchronized(monitorLock) {
            running = true
            monitor?.let { runCatching { it.interrupt() } }
            monitor = Thread {
                try {
                    val inp: InputStream = s.inputStream
                    val buf = ByteArray(64)
                    while (running && s.isConnected) {
                        val n = inp.read(buf)
                        if (n < 0) break // remote tutup (EOF) = putus
                    }
                } catch (_: Exception) {
                } finally {
                    if (running) {
                        // putus tak disengaja: tutup socket, beri tahu UI
                        close()
                    }
                }
            }.apply {
                isDaemon = true
                name = "z1label-monitor"
            }
            monitor?.start()
        }
    }

    private fun notifyConnected(v: Boolean) {
        if (notifiedConnected == v) return
        notifiedConnected = v
        onStateChange?.let { c ->
            runCatching { c(v) }
        }
    }
}
