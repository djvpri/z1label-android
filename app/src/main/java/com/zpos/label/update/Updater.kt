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

    /** Pesan error operasi update terakhir (utk log / kirim-WA). Null = sukses. */
    @Volatile var lastErr: String? = null

    /** Hook log detail update (di-set dari MainActivity -> Logger). */
    @Volatile var onLog: ((String) -> Unit)? = null

    /** Cek rilis terbaru dari GitHub. */
    fun cekTerbaru(): Rilis? {
        lastErr = null
        val conn = try {
            URL("$HOST/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        } catch (e: Exception) {
            lastErr = "cek: URL/open gagal: ${e.message}"; null
        }
        if (conn == null) return null
        conn.apply {
            connectTimeout = 12000; readTimeout = 12000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "z1label")
        }
        return try {
            val code = conn.responseCode
            if (code != 200) { lastErr = "cek: HTTP $code"; return null }
            val js = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val apk = js.getJSONArray("assets").let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it).optString("browser_download_url") }
                    .firstOrNull { it.endsWith(".apk") }
            }
            if (apk == null) lastErr = "cek: salah (tak ada asset .apk)"
            onLog?.invoke(if (apk == null) "cek: rilis ${js.optString("tag_name")} tanpa asset .apk"
                          else "cek: OK rilis=${js.optString("tag_name")} apk=tak(bisa)${apk.takeLast(24)}")
            Rilis(js.optString("tag_name"), apk)
        } catch (e: Exception) {
            lastErr = "cek: ${e::class.simpleName}: ${e.message}"
            onLog?.invoke("cek GAGAL: $lastErr")
            null
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
        lastErr = null
        val attempts = 6   // retry: jaringan flaky (Socket abort / DNS) sering butuh coba ulang
        val file = try {
            withContext(Dispatchers.IO) {
                val f = File(ctx.cacheDir, "z1label-update.apk")
                var yangTerak = "?"
                for (i in 1..attempts) {
                    val pos = if (i > 1) f.length() else 0L   // resume dari byte yang sudah ada
                    onLog?.invoke("unduh: try $i/$attempts byte=$pos $url")
                    try {
                        val conn = URL(url).openConnection() as HttpURLConnection
                        conn.connectTimeout = 20000; conn.readTimeout = 30000
                        if (pos > 0) conn.setRequestProperty("Range", "bytes=$pos-")
                        try {
                            val code = conn.responseCode
                            // 200 = server tak dukung Range (mulai 0); 206 = lanjut append
                            if (code != 200 && code != 206) {
                                yangTerak = "HTTP $code"
                                onLog?.invoke("unduh: try $i HTTP $code")
                                continue
                            }
                            val total = conn.getHeaderFieldInt("Content-Length", -1)
                            onLog?.invoke("unduh: try $i code=$code len=$total")
                            // 206=append ke akhir (resume dari pos), 200=overwrite dari 0 (fresh)
                            java.io.FileOutputStream(f, code == 206).use { out ->
                                conn.inputStream.copyTo(out)
                            }
                            if (f.exists() && f.length() > 0) {
                                onLog?.invoke("unduh: selesai bytes=${f.length()}")
                                return@withContext f                     // sukses
                            }
                            yangTerak = "file 0 byte"
                        } finally { conn.disconnect() }
                    } catch (e: Exception) {
                        yangTerak = "${e::class.simpleName}: ${e.message}"
                        onLog?.invoke("unduh: try $i GAGAL (pos=$pos, file=${f.length()}B): $yangTerak")
                        Thread.sleep(1200L)
                    }
                }
                lastErr = "download: GAGAL ${attempts}x — $yangTerak"
                onLog?.invoke("unduh: SEMUA GAGAL — $lastErr")
                null
            }
        } catch (e: Exception) {
            lastErr = "download: ${e::class.simpleName}: ${e.message}"
            null
        } ?: run { onLog?.invoke("installer: ABAIKAN — file null ($lastErr)"); return false }
        val act = ctx as? Activity ?: run { onLog?.invoke("installer: ABAIKAN — context bukan Activity"); return false }
        // buka system installer di MAIN thread; return = apakah installer berhasil dibuka.
        // (Jangan `return true` blind: ACTION_VIEW bisa gagal -> dulu log 'berhasil' palsu.)
        val terbuka = try {
            withContext(Dispatchers.Main) {
                val uri = FileProvider.getUriForFile(act, "${act.packageName}.fileprovider", file)
                val i = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    act.startActivity(i)
                    onLog?.invoke("installer: dibuka")
                    true
                } catch (e: Exception) {
                    // beberapa ROM (label/vendor) tolak ACTION_VIEW langsung — coba chooser
                    try {
                        act.startActivity(Intent.createChooser(i, "Buka installer"))
                        onLog?.invoke("installer: dibuka via chooser")
                        true
                    } catch (e2: Exception) {
                        lastErr = "buka installer: ${e::class.simpleName}: ${e.message} (chooser: ${e2.message})"
                        onLog?.invoke("installer GAGAL buka: $lastErr")
                        Toast.makeText(act, "Gagal buka installer: ${e.message}", Toast.LENGTH_LONG).show()
                        false
                    }
                }
            }
        } catch (e: Exception) {
            lastErr = "buka installer: ${e::class.simpleName}: ${e.message}"
            false
        }
        return terbuka
    }
}
