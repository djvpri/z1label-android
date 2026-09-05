package com.zpos.label.api

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import com.zpos.label.bc.Code128

/** Produk dari z1pos (GET /api/produk?semua=1). */
data class Produk(
    val id: Long,
    val nama: String,
    val barcode: String?, // barcode asli (EAN-13 asli / internal lama); bisa null
    val barcodeInternal: String?, // barcode internal 6-digit v3 utk label pendek (bila ada)
    val harga: String,
    val satuan: String?
)

/** Barcode utk LABEL. preferAsli=false (default): utamakan barcode_internal pendek
 *  (Code128-C tebal => terbaca 25mm), lalu barcode asli, lalu generate lokal 6-digit.
 *  preferAsli=true: utamakan barcode asli 13 digit (kode kemasan), lalu internal, lalu generate. */
fun barcodeLabel(p: Produk, preferAsli: Boolean = false): String {
    val asli = p.barcode?.takeIf { it.isNotBlank() }
    val internal = p.barcodeInternal?.takeIf { it.isNotBlank() }
    return if (preferAsli) asli ?: internal ?: Code128.generateV3(p.id)
    else internal ?: asli ?: Code128.generateV3(p.id)
}

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
                    barcodeInternal = o.optString("barcode_internal").takeIf { it.isNotEmpty() },
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

    /** Simpan produk baru ke server (tenant toko aktif). Stok awal = 20 dr app label. */
    fun simpanProduk(nama: String, harga: Double): Result<Long?> {
        if (cookie.isEmpty()) return Result.failure(IllegalStateException("Belum login"))
        return try {
            val body = JSONObject()
                .put("nama", nama)
                .put("harga", harga)      // server: z.number().positive
                .put("stok", 20)          // server default 0; app label simpan otomatis 20
                .toString().toByteArray(Charsets.UTF_8)
            val conn = URL("$baseUrl/api/produk").openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Cookie", "zpos_token=$cookie")
            try {
                conn.outputStream.use { it.write(body) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }?.take(400).orEmpty()
                if (code in 200..299) {
                    val id = try { JSONObject(text).optLong("id").takeIf { it > 0 } } catch (_: Exception) { null }
                    Result.success(id)
                } else {
                    val msg = try { JSONObject(text).optString("error").ifBlank { "HTTP $code" } } catch (_: Exception) { "HTTP $code: $text" }
                    Result.failure(IllegalStateException(msg))
                }
            } finally { conn.disconnect() }
        } catch (e: Exception) {
            Result.failure(e)
        }
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
