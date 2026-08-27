package com.zpos.label.bt

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.OutputStream
import java.lang.reflect.Method
import java.util.UUID

/**
 * Kirim bytes ESC/POS ke printer label Bluetooth Classic (SPP).
 * Android 12+ butuh BLUETOOTH_CONNECT runtime permission (di MainActivity).
 * UUID standar SPP (RFCOMM).
 */
object BluetoothPrinter {

    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var out: OutputStream? = null

    fun pairedDevices(): List<BluetoothDevice> {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
        return adapter.bondedDevices?.toList() ?: emptyList()
    }

    fun connect(address: String): String? {
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return "Bluetooth tidak tersedia"
            if (adapter.getRemoteDevice(address) == null) return "Perangkat tidak ditemukan"
            val dev = adapter.getRemoteDevice(address)

            // pakai UUID SPP > fallback ke createRfcommSocketToServiceRecord kuno
            var s = try {
                dev.createRfcommSocketToServiceRecord(SPP_UUID)
            } catch (_: Exception) { null }

            if (s == null) {
                // fallback metode refleksi (berapa printer tak daftarkan UUID SPP)
                val m: Method = dev.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                s = m.invoke(dev, 1) as BluetoothSocket
            }
            adapter.cancelDiscovery()
            s.connect()
            socket = s
            out = s.outputStream
            return null
        } catch (e: Exception) {
            close()
            return e.message ?: "Gagal konek"
        }
    }

    fun connected(): Boolean = socket?.isConnected == true

    fun write(bytes: ByteArray): String? {
        val o = out ?: return "Printer belum konek"
        return try {
            o.write(bytes)
            o.flush()
            null
        } catch (e: Exception) {
            e.message ?: "Gagal kirim"
        }
    }

    fun close() {
        try { out?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        out = null
        socket = null
    }
}
