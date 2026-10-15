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

/** Hasil gemini "foto -> nama" dari server z1pos (proxy, tak ada key di app). */
data class NamaDariFoto(
    val nama: String?,
    val harga: String?,     // harga terbaca jelas dari label (optional)
    val adaTeks: Boolean?,  // false = tak ada label keterbacaan; nama dr penampakan
    val error: String?      // non-null bila endpoint balas error (mis. key belum set)
)

object ZposApi {

    @Volatile var baseUrl: String = "https://z1pos.zomet.my.id"
    @Volatile var cookie: String = ""
    @Volatile var namaToko: String = ""
    @Volatile var namaUser: String = ""
    @Volatile var emailUser: String = ""
    @Volatile var planToko: String = ""      // "pro" | "free" | "demo" | ""
    @Volatile var tokoAktif: Boolean = true
    @Volatile var tokoExpired: Boolean = false

    /** Login pakai email+sandi z1pos (POST /api/auth/login, set cookie zpos_token).
     *  Juga baca body response utk nama toko (multi-tenant). */
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
            var gotCookie = false
            for (h in set) {
                h.split(";").forEach { p ->
                    val kv = p.trim().split("=", limit = 2)
                    if (kv.size == 2 && kv[0] == "zpos_token") { cookie = kv[1]; gotCookie = true }
                }
            }
            // Baca body sukses utk nama toko
            if (gotCookie && code in 200..299) {
                val respBody = conn.inputStream?.bufferedReader()?.use { it.readText() } ?: ""
                namaToko = parseNamaToko(respBody, email)
            }
            if (gotCookie) return null
            return conn.errorStream?.let { String(it.readBytes()) } ?: "Login gagal ($code)"
        } finally { conn.disconnect() }
    }

    /** Ambil nama toko dari berbagai kemungkinan field di JSON response login. */
    private fun parseNamaToko(body: String, email: String): String {
        if (body.isBlank()) return email
        return try {
            val o = JSONObject(body)
            // Coba berbagai field yg mungkin ada di response ZPos
            listOf("nama_toko", "toko", "store_name", "store", "toko_nama", "nama")
                .firstNotNullOfOrNull { key ->
                    o.optString(key).takeIf { it.isNotBlank() && it != "null" }
                }
                // Nested: user.nama_toko, user.toko, dst
                ?: listOf("user", "toko", "store")
                    .firstNotNullOfOrNull { parent ->
                        o.optJSONObject(parent)?.let { sub ->
                            listOf("nama", "nama_toko", "toko", "store_name")
                                .firstNotNullOfOrNull { key ->
                                    sub.optString(key).takeIf { it.isNotBlank() && it != "null" }
                                }
                        }
                    }
                ?: email
        } catch (_: Exception) { email }
    }

    /** GET /api/auth/me — fallback utk nama toko + profil user bila login response tak punya.
     *  Response ZPos: { nama, userName, email, plan, role, aktif, expired, isDemo, ... }
     *  Set field namaToko, namaUser, emailUser, planToko, tokoAktif, tokoExpired. */
    fun ambilProfil(): String {
        if (cookie.isEmpty()) return ""
        return try {
            val conn = open("$baseUrl/api/auth/me", null)
            try {
                if (conn.responseCode != 200) return ""
                val txt = conn.inputStream.bufferedReader().use { it.readText() }
                if (txt.isBlank() || txt == "null") return ""
                val o = JSONObject(txt)
                // nama toko (tenant) — field "nama" di /api/auth/me = nama toko fresh from DB
                namaToko = listOf("nama", "nama_toko", "toko", "store_name", "store")
                    .firstNotNullOfOrNull { key -> o.optString(key).takeIf { it.isNotBlank() && it != "null" } }
                    ?: namaToko
                namaUser = o.optString("userName").takeIf { it.isNotBlank() && it != "null" } ?: namaUser
                emailUser = o.optString("email").takeIf { it.isNotBlank() && it != "null" } ?: emailUser
                planToko = o.optString("plan").takeIf { it.isNotBlank() && it != "null" } ?: planToko
                tokoAktif = o.optBoolean("aktif", true)
                tokoExpired = o.optBoolean("expired", false)
                namaToko
            } finally { conn.disconnect() }
        } catch (_: Exception) { "" }
    }

    /** Cari produk berdasarkan barcode di DB ZPos sendiri.
     *  Return: null = tidak ketemu; non-null = sudah terdaftar (duplikat). */
    fun cekBarcodeDb(barcode: String): Produk? {
        if (cookie.isEmpty() || barcode.isBlank()) return null
        return try {
            val conn = open("$baseUrl/api/produk?semua=1&barcode=${java.net.URLEncoder.encode(barcode, "UTF-8")}", null)
            try {
                if (conn.responseCode != 200) return null
                val txt = conn.inputStream.bufferedReader().use { it.readText() }
                val arr = JSONArray(txt)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val bc = o.optString("barcode").takeIf { it.isNotEmpty() }
                    val bci = o.optString("barcode_internal").takeIf { it.isNotEmpty() }
                    if (bc == barcode || bci == barcode) {
                        return Produk(
                            id = o.optLong("id"),
                            nama = o.optString("nama").ifBlank { "Produk #${o.optLong("id")}" },
                            barcode = bc, barcodeInternal = bci,
                            harga = formatHarga(o.optString("harga")),
                            satuan = o.optString("satuan").takeIf { it.isNotEmpty() }
                        )
                    }
                }
                null
            } finally { conn.disconnect() }
        } catch (_: Exception) { null }
    }

    /** Lookup barcode di OpenFood Facts (gratis, no API key).
     *  Return: nama produk bila ketemu, null bila tidak. */
    fun lookupOpenFoodFacts(barcode: String): String? {
        if (barcode.length < 8) return null
        return try {
            val conn = URL("https://world.openfoodfacts.org/api/v2/product/$barcode.json?fields=product_name")
                .openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            conn.setRequestProperty("User-Agent", "Z1Label-Android/1.7")
            try {
                if (conn.responseCode != 200) return null
                val txt = conn.inputStream.bufferedReader().use { it.readText() }
                val o = JSONObject(txt)
                if (o.optInt("status") == 1) {
                    o.optJSONObject("product")?.optString("product_name")?.takeIf { it.isNotBlank() }
                } else null
            } finally { conn.disconnect() }
        } catch (_: Exception) { null }
    }

    /** Hasil lookup barcode: source + nama (bila ketemu). */
    data class BarcodeLookup(
        val source: String,   // "db" | "off" | "notfound"
        val nama: String?,    // nama produk bila ketemu
        val produkId: Long?   // ID bila sudah ada di DB
    )

    /** Lookup barcode berurutan: DB ZPos → OpenFood Facts → tidak ketemu. */
    fun lookupBarcode(barcode: String): BarcodeLookup {
        // 1. Cek DB sendiri
        val existing = cekBarcodeDb(barcode)
        if (existing != null) return BarcodeLookup("db", existing.nama, existing.id)
        // 2. OpenFood Facts
        val offName = lookupOpenFoodFacts(barcode)
        if (offName != null) return BarcodeLookup("off", offName, null)
        // 3. Tidak ketemu
        return BarcodeLookup("notfound", null, null)
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

    /** Kategori id (tenant aktif) yg dipakai utk produk baru: preferensi "umum", fallback pertama. */
    fun kategoriUtama(): Result<Long> {
        return try {
            val conn = open("$baseUrl/api/kategori", null)
            try {
                val code = conn.responseCode
                if (code !in 200..299) return Result.failure(IllegalStateException("Gagal muat kategori (HTTP $code)"))
                val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val arr = try { org.json.JSONArray(text) } catch (_: Exception) {
                    org.json.JSONObject(text).optJSONArray("rows")
                        ?: org.json.JSONObject(text).optJSONArray("data")
                        ?: org.json.JSONArray()
                }
                // preferensi dgn nama unit umum dulu (terjemahan bhs-server tak tentu), lalu baris pertama.
                val id = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    o.optLong("id") to o.optString("nama").lowercase()
                }.filter { it.first > 0L }.let { list ->
                    val umum = list.find { it.second.contains("umum") || it.second == "lain" || it.second.contains("semua") }
                    umum?.first ?: list.firstOrNull()?.first
                }
                if (id == null) Result.failure(IllegalStateException("Belum ada kategori. Buat dulu kategori di ZPos."))
                else Result.success(id)
            } finally { conn.disconnect() }
        } catch (e: Exception) { Result.failure(e) }
    }

    /** Simpan produk baru ke server (tenant toko aktif). Stok awal = 20 dr app label.
     *  @param barcode barcode asli produk (EAN-13/Code128) — opsional, dikirim bila ada. */
    fun simpanProduk(nama: String, harga: Double, kategoriId: Long, barcode: String? = null): Result<Long?> {
        if (cookie.isEmpty()) return Result.failure(IllegalStateException("Belum login"))
        return try {
            val json = JSONObject()
                .put("nama", nama)
                .put("harga", harga)      // server: z.number().positive
                .put("stok", 20)          // server default 0; app label simpan otomatis 20
                .put("kategori_id", kategoriId)   // WAJIB: tanpa ini zod server tolak validasi
            if (!barcode.isNullOrBlank()) json.put("barcode", barcode)
            val body = json.toString().toByteArray(Charsets.UTF_8)
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

    /** Foto → nama (proxy server z1pos → Gemini). Kirim data URI stl dikompres app. */
    fun namaDariFoto(fotoDataUri: String): Result<NamaDariFoto> {
        if (cookie.isEmpty()) return Result.failure(IllegalStateException("Belum login"))
        return try {
            val body = JSONObject().put("foto", fotoDataUri).toString().toByteArray(Charsets.UTF_8)
            val conn = URL("$baseUrl/api/produk/nama-dari-foto").openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 40000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Cookie", "zpos_token=$cookie")
            try {
                conn.outputStream.use { it.write(body) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }?.take(2000).orEmpty()
                if (code in 200..299) {
                    val o = try { JSONObject(text) } catch (_: Exception) { JSONObject() }
                    Result.success(NamaDariFoto(
                        nama = o.optString("nama").takeIf { it.isNotBlank() },
                        harga = o.optString("harga").takeIf { it.isNotBlank() && it != "null" },
                        adaTeks = if (o.has("adaTeks") && !o.isNull("adaTeks")) o.optBoolean("adaTeks") else null,
                        error = null
                    ))
                } else {
                    val msg = try { JSONObject(text).optString("error").ifBlank { "HTTP $code" } } catch (_: Exception) { "HTTP $code" }
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
