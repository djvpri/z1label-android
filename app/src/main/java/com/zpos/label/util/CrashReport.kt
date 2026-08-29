package com.zpos.label.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Penangkap crash global (force-close / app keluar sendiri).
 * Pasang di onCreate sebelum apa pun -> tulis stacktrace ke file crash.log
 * sebelum app mati, biar bisa dikirim via tombol "Log" (WhatsApp).
 * Teruskan ke handler bawaan sesudahnya (app tetap mati normal).
 */
object CrashReport {

    @Volatile private var installed = false

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            val st = e.stackTraceToString()
            val stamp = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(Date())
            runCatching {
                File(ctx.filesDir, "crash.log").writeText("=== $stamp ===\n$st\n")
            }
            // jangan lengah: tetap teruskan agar perilaku mati sistem normal
            prev?.uncaughtException(thread, e)
        }
    }

    /** Ambil teks crash terakhir (atau kosong). */
    fun ambil(ctx: Context): String =
        runCatching { File(ctx.filesDir, "crash.log").readText() }.getOrNull() ?: ""
}
