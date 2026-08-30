package com.zpos.label.api

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Produk dari z1pos (GET /api/produk?semua=1). */
data class Produk(
    val id: Long,
    val nama: String,
    val barcode: String?, // bisa null -> yg baru di-generate pakai Code128.generateV3
    val harga: String,
    val satuan: String?
)

object ZposApi {

    @Volatile var baseUrl: String = "https://z1pos.zomet.my.id"
    @Volatile var cookie: String = ""

    /** Login pakai email+sandi z1pos (POST /api/auth/login, set cookie zpos_token). */
    fun login(email: String, sandi: String): String? {
        val body = "{\"email\":${JSONObject.quote(email)},\"password\":${JSONObject.quote(sandi)}}"
        val conn = URL("$baseUrl/api/auth/login").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val set = conn.headerFields?.get("set-cookie") ?: emptyList()
            for (h in set) {
                h.split(";").forEach { p ->
                    val kv = p.trim().split("=", limit = 2)
                    if (kv.size == 2 && kv[0] == "zpos_token") { cookie = kv[1]; return null }
                }
            }
            return conn.errorStream?.let { String(it.readBytes()) } ?: "Login gagal ($code)"
        } finally { conn.disconnect() }
    }

    /** Ambil semua produk aktif toko. */
    fun daftarProduk(): Result<List<Produk>> {
        if (cookie.isEmpty()) return Result.failure(IllegalStateException("Belum login"))
        val conn = open("$baseUrl/api/produk?semua=1", null)
        try {
            val code = conn.responseCode
            if (code != 200) return Result.failure(IllegalStateException("HTTP $code"))
            val txt = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(txt) // ?semua=1 -> array langsung (cek route)
            val list = ArrayList<Produk>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(Produk(
                    id = o.optLong("id"),
                    nama = listOf("nama", "name", "nama_produk")
                        .firstNotNullOfOrNull { o.optString(it).takeIf { it.isNotBlank() } } ?: "",
                    barcode = o.optString("barcode").takeIf { it.isNotEmpty() },
                    harga = formatHarga(o.optString("harga")),
                    satuan = o.optString("satuan").takeIf { it.isNotEmpty() }
                ))
            }
            return Result.success(list)
        } finally { conn.disconnect() }
    }

    private fun formatHarga(v: String): String {
        return try {
            val n = v.toDouble()
            "Rp " + String.format("%,.0f", n).replace(',', '.')
        } catch (_: Exception) { v }
    }

    private fun open(url: String, body: ByteArray?): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.setRequestProperty("Cookie", "zpos_token=$cookie")
        if (body != null) {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(body) }
        }
        conn.connect()
        return conn
    }
}
