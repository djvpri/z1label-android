package com.zpos.label.util

import android.content.Context
import com.zpos.label.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Logger ringkas utk sesi app — dipakai buat "kirim log error via WhatsApp".
 * Menyimpan baris log ke SharedPreferences (aman lintas restart, cap panjang).
 * Dipanggil di titik error/status utama (login, load, connect printer, cetak, update).
 */
object Logger {

    private const val KEY = "z1label_log"
    private const val MAX = 5000   // cap chars, singkirkan log terlama bila penuh

    @Volatile private var _cache = ""

    private fun prefs(c: Context) = c.getSharedPreferences("z1label", Context.MODE_PRIVATE)

    fun log(c: Context, tag: String, msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val line = "[$ts] $tag: $msg\n"
        synchronized(this) {
            var cur = _cache.ifEmpty { prefs(c).getString(KEY, "") ?: "" }
            cur = (cur + line).takeLast(MAX)
            _cache = cur
            prefs(c).edit().putString(KEY, cur).apply()
        }
    }

    /** Ambil seluruh log (headers app + isi sesi). */
    fun ambil(c: Context): String {
        val s = _cache.ifEmpty { prefs(c).getString(KEY, "") ?: "" }
        val v = try { BuildConfig.VERSION_NAME } catch (e: Exception) { "?" }
        return "Z1 Label v$v — log ${SimpleDateFormat("dd/MM HH:mm", Locale.US).format(Date())}\n---\n" + s.ifEmpty { "(kosong)" }
    }

    fun kosongkan(c: Context) {
        synchronized(this) { _cache = ""; prefs(c).edit().remove(KEY).apply() }
    }
}
