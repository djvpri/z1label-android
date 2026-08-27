package com.zpos.label.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Auto-updater sederhana via GitHub Releases.
 *
 * Alur:
 * 1. GET /repos/djvpri/z1label-android/releases/latest  -> tag_name + asset apk
 * 2. bandingkan versi (semantic) dgn BuildConfig.VERSION_NAME
 * 3. kalau ada baru: unduh APK ke cacheDir -> FileProvider URI -> ACTION_VIEW install
 *
 * CATATAN PLATFORM: Android TIDAK bisa instal APK diam-diam tanpa interaksi user
 * (kecuali rooted). "Auto update" di sini = unduh otomatis + buka installer,
 * user tinggal sekali ketuk "Install". Sumber: arsip rilis di GitHub.
 */
object Updater {

    const val REPO = "djvpri/z1label-android"
    private const val HOST = "https://api.github.com"

    data class Rilis(val versi: String, val apkUrl: String?)

    /** Cek rilis terbaru dari GitHub. */
    fun cekTerbaru(): Rilis? {
        val conn = URL("$HOST/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        conn.apply {
            connectTimeout = 12000; readTimeout = 12000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "z1label")
        }
        return try {
            val code = conn.responseCode
            if (code != 200) return null
            val js = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val apk = js.getJSONArray("assets").let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it).optString("browser_download_url") }
                    .firstOrNull { it.endsWith(".apk") }
            }
            Rilis(js.optString("tag_name"), apk)
        } finally {
            conn.disconnect()
        }
    }

    /** Bandingkan "1.2.3" > "1.2.2". */
    fun lebihBaru(a: String, b: String): Boolean {
        val pa = a.trimStart('v').split('.').map { it.toIntOrNull() ?: 0 }
        val pb = b.trimStart('v').split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }; val y = pb.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Download APK ke cache lalu minta install (buka system installer). */
    suspend fun unduhDanInstall(ctx: Context, url: String): Boolean {
        val file = withContext(Dispatchers.IO) {
            val f = File(ctx.cacheDir, "z1label-update.apk")
            f.delete()
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 20000; conn.readTimeout = 60000
            if (conn.responseCode != 200) null
            else conn.inputStream.use { inp -> f.outputStream().use { out -> inp.copyTo(out) } }.let { f }
        } ?: return false
        val act = ctx as? Activity ?: return false
        act.runOnUiThread {
            try {
                val uri = FileProvider.getUriForFile(act, "${act.packageName}.fileprovider", file)
                val i = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                act.startActivity(i)
            } catch (e: Exception) {
                Toast.makeText(act, "Gagal buka installer: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        return true
    }
}
