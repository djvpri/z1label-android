package com.zpos.label.util

import android.content.Context
import com.zpos.label.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logger ringkas utk sesi app — dipakai buat "kirim log error via WhatsApp".
 * Menyimpan baris log ke SharedPreferences (aman lintas restart, cap panjang).
 * Baris >30 menit TIDAK ditampilkan (hanya log terakhir 30 menit yg relevan).
 * Dipanggil di titik error/status utama (login, load, connect printer, cetak, update).
 */
object Logger {

    private const val KEY = "z1label_log"
    private const val MAX = 5000           // cap chars
    private const val RM = 30 * 60 * 1000L // 30 menit dalam ms
    private const val PREFIX = "\u0001"    // penanda baris modern (bawa epoch)

    @Volatile private var _cache = ""

    private fun prefs(c: Context) = c.getSharedPreferences("z1label", Context.MODE_PRIVATE)

    fun log(c: Context, tag: String, msg: String) {
        val e = System.currentTimeMillis()
        val read = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val line = "$PREFIX$e|[$read] $tag: $msg\n"
        synchronized(this) {
            var cur = _cache.ifEmpty { prefs(c).getString(KEY, "") ?: "" }
            cur = (cur + line).takeLast(MAX)
            _cache = cur
            prefs(c).edit().putString(KEY, cur).apply()
        }
    }

    /** Ambil log 30 menit terakhir (headers app + isi). Baris lama dibuang. */
    fun ambil(c: Context): String {
        synchronized(this) {
            if (_cache.isEmpty()) _cache = prefs(c).getString(KEY, "") ?: ""
            val s = _cache
            val now = System.currentTimeMillis()
            val keep = s.lineSequence()
                .filter { it.isNotBlank() }
                .filter { it.startsWith(PREFIX) }
                .map { ln ->
                    val sep = ln.indexOf('|')
                    if (sep <= 0) null else {
                        val e = ln.substring(1, sep).toLongOrNull() ?: 0L
                        now - e <= RM
                    }
                }
                .toList()
            // ambil baris modern yg masih dalam 30 mnt (urutan tetap)
            val filtered = StringBuilder()
            var idx = 0
            for (ln in s.lineSequence()) {
                if (ln.isNotBlank() && idx < keep.size && keep[idx]) filtered.append(ln).append('\n')
                idx++
            }
            val v = try { BuildConfig.VERSION_NAME } catch (e: Exception) { "?" }
            return "Z1 Label v$v — log ${SimpleDateFormat("dd/MM HH:mm", Locale.US).format(Date())} (30 mnt terakhir)\\n---\\n" +
                (filtered.toString().ifEmpty { "(tidak ada log dlm 30 mnt terakhir)" })
        }
    }

    fun kosongkan(c: Context) {
        synchronized(this) { _cache = ""; prefs(c).edit().remove(KEY).apply() }
    }
}
