package com.zpos.label.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Penangkap crash global (force-close / app keluar sendiri).
 * `install()` pasang di onCreate sebelum apa pun -> handler default-navigate
 * tulis stacktrace ke file crash.log sebelum app mati + ke log sesi (Logger),
 * biar bisa dikirim via tombol "Log" (WhatsApp). Teruskan ke handler bawaan
 * sesudahnya (app tetap mati normal) utk crash murni.
 *
 * `catat(ctx, throwable)` dipakai oleh CoroutineExceptionHandler (scope) utk
 * menangkap exception coroutine yang dulu membikin app keluar diam-diam — di-log
 * tanpa membunuh app.
 */
object CrashReport {

    @Volatile private var installed = false

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { catat(ctx, e, "UNCAUGHT ${thread.name}") }
            // jangan lengah: tetap teruskan agar perilaku mati sistem normal
            prev?.uncaughtException(thread, e)
        }
    }

    /** Catat crash/error sah ke crash.log + log sesi (untuk kirim via tombol Log). */
    fun catat(ctx: Context, e: Throwable, tag: String) {
        val st = e.stackTraceToString()
        val stamp = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(Date())
        runCatching {
            File(ctx.filesDir, "crash.log")
                .appendText("[$tag] $stamp\n$st\n---\n")
        }
        runCatching { Logger.log(ctx, "crash", "[$tag] ${e::class.simpleName}: ${e.message}") }
    }

    /** Ambil teks crash terakhir (atau kosong). */
    fun ambil(ctx: Context): String =
        runCatching { File(ctx.filesDir, "crash.log").readText() }.getOrNull() ?: ""
}
