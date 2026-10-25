package com.zpos.label.escpos

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.zpos.label.bc.Code128
import com.zpos.label.bc.Ean13
import java.io.ByteArrayOutputStream
import kotlin.math.ceil

/**
 * Bangun label barcode 25x15mm sbg satu bitmap hitam-putih (ralas dots), lalu
 * kirim ke printer sebagai raster ESC/POS `GS v 0` (monochrome). Kompatibel
 * printer label thermal (TSPL/ESC-POS) via Bluetooth SPP.
 *
 * Pendekatan bitmap penuh: UI/Nama/Harga digambar di Android (font konsisten,
 * High-DPI), bukan font printer — hasil identik di printer apa pun.
 */
object EscPosLabel {

    const val DOTS_PER_MM = 8.0 // 203 dpi thermal
    /** Printer label ini mencetak geometri BARCODE ~0,73x dari angka perintah (terukur dari 2
     *  foto cetak 40x30: lebar 173/237 & 174/237, tinggi 99/140 & 104/140). Kompensasi supaya
     *  bar mengisi label. Hanya dipakai jalur label besar (40x30); ukuran lain tak berubah. */
    private const val TSPL_BC_SCALE = 0.73
    /** Lebar & tinggi glyph nyata per multiplier (terukur dari cetakan v1.8.0 40x30: nama
     *  "…" 24 char = 237 dot -> 9,9; digit ×3 = 60 dot -> 20/mult). Dokumentasi font "1" = 8x12
     *  TIDAK cocok dgn printer ini; pakai angka terukur supaya center & clip benar. */
    private const val CW_BIG = 10
    private const val CH_BIG = 20
    /** Skala cetak NYATA vs angka perintah (terukur foto cetak v1.8.4 40×30, 3 titik:
     *  lebar simbol 239/316 = 0,757; perintah y=128 → 96 dot, y=208 → 155 dot;
     *  tinggi barcode 78 → 59 dot). Semua dimensi dipampatkan ~0,75×, jadi isi label
     *  hanya memakai ~75% kanvas: nama mentok tepi atas & sisa ~8 mm kosong di bawah. */
    private const val LAYOUT_SCALE = 0.75
    /** Lead-in barcode TSPL (terukur dari cetakan v1.8.4 40×30, foto user): perintah
     *  `BARCODE x` dengan x=45 dot ternyata mencetak simbol mulai dot **59** — printer
     *  menambah ~14 dot di kiri. Tanpa kompensasi quiet zone jadi 7,4 mm / 2,7 mm
     *  (asimetris, sisi kanan < 10 modul). Kurangi x sebesar ini supaya simetris. */
    private const val BIG_BC_LEAD = 14
    // Kanvas perintah utk label 30x20. Printer mencetak 0,73x -> 30 mm / 0,73 x 8 = 329 dot.
    private const val MED_W = 329
    private const val MED_H = 219
    // Kanvas perintah utk label 50x25: 50 mm / 0,73 x 8 = 548 dot.
    private const val WIDE_W = 548
    private const val WIDE_H = 274
    // SKALA CETAK TERUKUR (penggaris, label kalibrasi v1.8.13 pada printer user):
    //   batang 10 cmd -> 1,0 mm dan 40 cmd -> 4,0 mm  =>  0,100 mm/cmd  =>  skala 0,80.
    // Konfirmasi silang: kanvas lama 274 dot utk label 25 mm TERPOTONG di tepi kanan
    // (274 x 0,73 = 25,0 mm pas; 274 x 0,80 = 27,4 mm -> lebih 2,4 mm). Jadi 0,73 salah.
    private const val PRINT_SCALE = 0.80
    /**
     * 25x15: area cetak efektif TERUKUR lewat kotak uji (bukan tebakan).
     * Disetujui user dari penggaris: area 150x80 cmd (15,0 x 8,0 mm) di offset x=200, y=0,
     * di dalam SIZE 610x150 (geometri yg sama dgn kotak uji v1.8.23 yg posisinya "sempurna").
     */
    private const val SMALL_X0 = 200          // offset kiri area cetak (cmd)
    private const val SMALL_Y0 = 0
    private const val SMALL_AW = 150          // lebar area cetak
    private const val SMALL_AH = 120          // tinggi area cetak (12 mm) utk barcode 5 mm
    /**
     * Lebar barcode = lebar area cetak (150 cmd), BUKAN dilebarkan.
     * Uji v1.8.30 (satu label, 3 variasi): V1 (170 cmd -> simbol 158 cmd) TERPOTONG,
     * V3 (wide 2x) terlalu kecil, V2 (n=1, simbol 79 cmd) satu-satunya yg utuh.
     * 170 cmd dipakai lagi => simbol 158 cmd, ujung 364 > batas area 350 -> terpotong.
     */
    private const val SMALL_BW = 150
    private const val SMALL_SIZE_W = 610      // SIZE kanvas (sama dgn kotak uji)
    private const val SMALL_SIZE_H = 150
    /**
     * Offset GAP. WAJIB 0. Diuji v1.8.27 (nilai 10): printer menggeser origin jauh lebih
     * besar dari 10 dot -> elemen paling bawah (barcode) keluar dari area cetak dan hilang,
     * sementara nama/harga tetap terlihat. Arti parameter ke-2 `GAP` tidak konsisten antar
     * firmware, jadi jangan dipakai untuk menggeser posisi.
     */
    private const val SMALL_GAP_OFS = 0
    // Kanvas perintah utk label 40x20: 40 mm / 0,73 x 8 = 438 dot, 20 mm / 0,73 x 8 = 219 dot.
    private const val W20_W = 438
    private const val W20_H = 219

    /** Buat label 25x15mm: nama + (harga opt) + barcode 128-C v3. */
    fun buatLabel(
        nama: String,
        harga: String,
        barcode: String,
        widthMm: Int = 25,
        heightMm: Int = 15
    ): Bitmap {
        val w = (widthMm * DOTS_PER_MM).toInt().coerceAtLeast(150) // 200 dot @25mm
        val h = (heightMm * DOTS_PER_MM).toInt().coerceAtLeast(100) // 120 dot @15mm
        val bigL = h >= 240
        val hargaShow = if (bigL) ribuIdn(harga) else harga

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        val sc = w / 25f // skala per-mm dalam dot

        // --- nama (kecil, tipis) ---
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        paint.textSize = sc * 2.2f
        paint.textAlign = Paint.Align.CENTER
        val namaClipped = clipText(nama, paint, w - (sc * 2), ellipsis = true)
        c.drawText(namaClipped, w / 2f, sc * 3f, paint)

        // --- harga (tipis, agak besar) ---
        paint.textSize = sc * 3.2f
        c.drawText(hargaShow, w / 2f, sc * 7.4f, paint)

        // --- barcode (isi ruang tersisa, kiri-kanan) ---
        val bc = Code128.encodeCDigits(barcode) ?: Code128.encodeBText(barcode)
        val heightPx = (h - (sc * 8.4f)).toInt().coerceAtLeast(30) // ruang bawah harga
        drawBarcode(c, bc, heightPx, h - heightPx - sc.toInt(), sc, w)

        return bmp
    }

    /**
     * Preview label utk ditampilkan di layar sebelum cetak. Render nama+harga (Paint,
     * mirror buatLabel) + BARCODE SESUAI proto tspl: EAN-13 bitmap (2 dot/modul, penuh
     * lebar) utk 13-digit, Code128 utk lainnya. Jadi preview = yg akan tercetak TSPL.
     */
    fun previewBitmap(
        nama: String,
        harga: String,
        barcode: String,
        widthMm: Int = 25,
        heightMm: Int = 15,
        barcode2d: Boolean = false
    ): Bitmap {
        val w = (widthMm * DOTS_PER_MM).toInt().coerceAtLeast(150)
        val h = (heightMm * DOTS_PER_MM).toInt().coerceAtLeast(100)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        val sc = w / 25f
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        paint.textAlign = Paint.Align.CENTER
        // 40x30: posisi preview mengikuti cetakan (nama 1,7 mm dari tepi atas, harga s/d 9,6 mm,
        // barcode 13,3–20,4 mm, nomor ~20,6 mm) — jangan pakai rumus sc*3/sc*7.4 yg mentok atas.
        val big = h >= 240
        // 30x20 (sedang): mirror cetakan — nama 1,3-3,1 mm, harga 3,5-7,1 mm, barcode 8,4-15,7 mm,
        // nomor 16,8-18,6 mm dari label 20 mm (kanvas perintah 329x219, printer mengecil 0,73x).
        val med = !big && w == 240 && h == 160
        // 50x25 (desain B): nama 1,3-3,1 mm, harga 4,0-9,5 mm, garis 10,8 mm,
        // barcode 13,3-20,8 mm, nomor 21,4-23,2 mm dari label 25 mm.
        val wide = !big && w == 400 && h == 200
        // 25x15 (desain A): nama 0,73-2,55 mm, harga 2,92-6,57 mm, barcode 7,30-11,86 mm,
        // nomor 12,23-14,05 mm dari label 15 mm.
        val small = !big && w == 200 && h == 120
        // 40x20: kanvas 438x219 -> nama 1,28-3,10 mm, harga 3,47-8,94 mm, barcode 9,86-16,24 mm,
        // nomor 16,79-18,61 mm dari label 20 mm.
        val w20 = !big && w == 320 && h == 160
        val yNama = if (big) h * 0.062f
                    else if (med) h * 0.155f
                    else if (wide) h * 0.083f
                    else if (small) h * 0.0f
                    else if (w20) h * 0.064f
                    else sc * 3f
        val yHarga = if (big) h * 0.335f
                     else if (med) h * 0.355f
                     else if (wide) h * 0.35f
                     else if (small) h * 0.20f
                     else if (w20) h * 0.173f
                     else sc * 7.4f
        paint.textSize = sc * 2.2f
        c.drawText(clipText(nama, paint, w - (sc * 2), ellipsis = true), w / 2f, yNama, paint)
        paint.textSize = sc * 3.2f
        val hsl = if (big) ribuIdn(harga) else harga
        c.drawText(hsl, w / 2f, yHarga, paint)

        val topBar = if (big) (h * 0.445f).toInt()
                     else if (med) (h * 0.42f).toInt()
                     else if (wide) (h * 0.533f).toInt()
                     else if (small) (h * 0.467f).toInt()
                     else if (w20) (h * 0.493f).toInt()
                     else (h - (sc * 8.4f)).toInt().coerceAtLeast(30)
        if (wide) {
            // Garis pemisah harga↔barcode (mirror BAR di jalur cetak, 448/548 lebar kanvas).
            val lp = Paint().apply { color = Color.BLACK }
            val lx = (w * 0.091f).toInt()
            c.drawRect(lx.toFloat(), (h * 0.431f), (w - lx).toFloat(), (h * 0.431f) + 2f, lp)
        }
        val bcPaint = Paint().apply { color = Color.BLACK }
        if (barcode2d) {
            // QR via ZXing — render sesuai mode 2D (padat, tebal) sama seperti print
            val (mQ, xQ) = previewQrGeo(barcode, w, h, topBar)
            val sizePx = 29 * mQ
            val qr = com.google.zxing.qrcode.QRCodeWriter().encode(
                barcode, com.google.zxing.BarcodeFormat.QR_CODE, sizePx, sizePx,
                mapOf(com.google.zxing.EncodeHintType.MARGIN to 1)
            )
            val px = xQ
            for (yy in 0 until sizePx) {
                for (xx in 0 until sizePx) {
                    if (qr.get(xx, yy)) c.drawRect((px + xx).toFloat(), (topBar + yy).toFloat(), (px + xx + 1).toFloat(), (topBar + yy + 1).toFloat(), bcPaint)
                }
            }
        } else if (barcode.length == 13 && barcode.all { it.isDigit() }) {
            val ndot = (w / Ean13.TOTAL_MODUL).coerceAtLeast(2)
            val bw = 95 * ndot
            val x0 = ((w - bw) / 2).coerceAtLeast(1)
            // 40x30: tinggi barcode 24% label (sama dgn jalur Code128), bukan sisa ke tepi bawah.
            val hpx = if (big) (h * 0.24f).toInt()
                      else if (med) (h * 0.365f).toInt()
                      else if (wide) (h * 0.30f).toInt()
                      else (h - topBar - (sc * 1f).toInt()).coerceAtLeast(20)
            for (b in Ean13.encodeBars(barcode)) {
                val xa = x0 + b.xModul * ndot
                c.drawRect(xa.toFloat(), topBar.toFloat(), (xa + b.wModul * ndot).toFloat(), (topBar + hpx).toFloat(), bcPaint)
            }
        } else {
            val bc = Code128.encodeCDigits(barcode) ?: Code128.encodeBText(barcode)
            // Label 40x30 (h>=240): barcode penuh mengisi sisa + nomor barcode di bawah (mirror TSPL).
            val bigL = h >= 240
            // small (25x15 area 15x8 mm) TIDAK mencetak nomor: tinggi 80 cmd hanya muat
            // nama + harga x2 + barcode. Kalau nomor dipaksa, barcode terpotong.
            val letakNomor = bigL || med || wide || w20
            // bigL (40x30): barcode 24% tinggi label (cetak 12,8 mm) + nomor di bawahnya.
            // med (30x20): barcode 36,5% (cetak 7,3 mm) + nomor. wide (50x25): 30% + nomor.
            val hpx = if (bigL) (h * 0.24f).toInt()
                      else if (med) (h * 0.365f).toInt()
                      else if (wide) (h * 0.30f).toInt()
                      else if (small) (h * 0.133f).toInt()
                      else if (w20) (h * 0.319f).toInt()
                      else h - topBar
            drawBarcode(c, bc, hpx, topBar, sc, w)
            if (letakNomor) {
                val txt = clipText(barcode, paint, w - (sc * 2))
                paint.textSize = sc * 0.9f
                c.drawText(txt, w / 2f, topBar + hpx + (if (med) sc * 1.1f else if (wide) sc * 1.3f else if (small) sc * 0.4f else if (w20) sc * 1.0f else sc * 1.6f), paint)
            }
        }
        return bmp
    }

    private fun drawBarcode(
        c: Canvas,
        bc: Code128.Encoded,
        heightPx: Int,
        top: Int,
        sc: Float,
        w: Int
    ) {
        val totalDot = (bc.totalModul + Code128.QUIET * 2) * 2 // modul=2 dot minimal
        val scale = (w - (sc * 2)).coerceAtLeast(1f).toFloat() / totalDot.toFloat()
        val leftPad = ((w - (bc.totalModul + Code128.QUIET * 2) * 2 * scale) / 2f)
        val paint = Paint().apply { color = Color.BLACK }
        for (bar in bc.bars) {
            val x = leftPad + (bar.xModul + Code128.QUIET) * 2 * scale
            val ww = (bar.wModul * 2 * scale).coerceAtLeast(2f)
            c.drawRect(x, top.toFloat(), x + ww, (top + heightPx).toFloat(), paint)
        }
    }

    private fun clipText(t: String, p: Paint, maxW: Float, ellipsis: Boolean = false): String {
        if (p.measureText(t) <= maxW) return t
        if (!ellipsis) {
            var s = t
            while (s.isNotEmpty() && p.measureText(s) > maxW) s = s.dropLast(1)
            return s.takeIf { it.isNotEmpty() } ?: "."
        }
        // NAMA kepanjangan: sisakan tempat utk "..." (1 baris, ujung berpola).
        val mark = "..."
        var s = t
        while (s.length > mark.length && p.measureText(s + mark) > maxW) s = s.dropLast(1)
        if (s.length > mark.length && p.measureText(s + mark) <= maxW) return s + mark
        // Label amat sempit: drop ke-3-titik pun tak muat → fallback potong polos.
        while (s.isNotEmpty() && p.measureText(s) > maxW) s = s.dropLast(1)
        return s.takeIf { it.isNotEmpty() } ?: "."
    }

    /** Normalisasi Harga jadi titik ribuan ("Rp 50000"/"50000"/"Rp 50.000" -> "Rp 50.000"). Idempoten. */
    private fun ribuIdn(s: String): String {
        val dig = s.filter { it.isDigit() }
        return try {
            val n = dig.toLong()
            "Rp " + String.format("%,d", n).replace(',', '.')
        } catch (_: Exception) { s }
    }

    /** Bitmap -> raster bytes `GS v 0 m 0 xL xH yL yH d...`. */
    fun toEscPosRaster(bmp: Bitmap): ByteArray {
        // konversi ke 1-bit (setiap pixel: hitam=1) sebelum diraster — lakukan manual
        val w = bmp.width
        val h = bmp.height
        val bytesPerRow = (w + 7) / 8
        val data = ByteArray(bytesPerRow * h)
        var i = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val px = bmp.getPixel(x, y)
                val r = Color.red(px); val g = Color.green(px); val b = Color.blue(px)
                val lum = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                if (lum < 128) {
                    val byteIdx = y * bytesPerRow + (x / 8)
                    data[byteIdx] = (data[byteIdx].toInt() or (0x80 shr (x % 8))).toByte()
                }
            }
        }
        val xL = (w % 256).toByte(); val xH = (w / 256).toByte()
        val yL = (h % 256).toByte(); val yH = (h / 256).toByte()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x1d, 0x76, 0x30, 0x00))
        out.write(byteArrayOf(xL, xH, yL, yH))
        out.write(data)
        return out.toByteArray()
    }

    /** Rangkaian beberapa label: feed + raster per label + cut/final feed (ESC/POS). */
    fun buatRun(labels: List<Bitmap>): ByteArray {
        val out = ByteArrayOutputStream()
        for (b in labels) {
            out.write(byteArrayOf(0x0c)) // form feed reset
            out.write(toEscPosRaster(b))
            out.write(byteArrayOf(0x0a)) // line feed
            out.write(byteArrayOf(0x0a, 0x0a)) // gap antar label
        }
        out.write(byteArrayOf(0x1c, 0x56, 0x00)) // ESC V (partial cut), tak semua
        return out.toByteArray()
    }

    // ---- TSPL (printer label clabel/Zebra/Xprinter/GODEX) ----

    data class LabelT(val nama: String, val harga: String, val bc: String)

    /**
     * Bangun urutan perintah TSPL utk label 25x15mm: SIZE/GAP/CLS per label,
     * teks nama+harga pakai font built-in, barcode pakai BARCODE built-in
     * (printer render sendiri, tajam & hemat). Koordinat dalam dot (203 dpi).
     */
    fun buatRunTSPL(
        ls: List<LabelT>,
        widthMm: Int = 25,
        heightMm: Int = 15,
        includeNama: Boolean = true,
        fontMul: Int = 1,
        barcode2d: Boolean = false
    ): ByteArray {
        val w = (widthMm * DOTS_PER_MM).toInt()   // 200
        val h = (heightMm * DOTS_PER_MM).toInt()  // 120
        val out = ByteArrayOutputStream()
        val eol = "\r\n"
        // ---------- layout adaptif (hindari baris menimpa saat font diperbesar) ----------
        // Nama (1 baris) + harga + barcode selalu utk SEMUA ukuran label (termasuk 25x15).
        // Turunkan multiplier efektif supaya 3 baris muat label pendek (h kecil); barcode ambil
        // sisa bawah dibatasi (≤44) biar muat dalam SIZE & tak kebas dari label.
        val fm = fontMul
            .let { m -> var mm = m; while ((16 * mm * 2 + 40) > h && mm > 1) mm--; mm }
        val fh = 16 * fm
        val gap = 6
        val yN = 4
        val yH = yN + fh + gap   // baris ke-2 (harga)
        val yB = yH + fh + gap   // barcode bawah (nama+harga)
        // Label 40x30 (h>=240): barcode MEMBESAR penuh mengisi sisa label (bidang yB..h)
        // + NOMOR BARCODE dicetak di bawah garis. Ukuran lain (25x15=120, 30x20/40x20=160,
        // 50x25=200) MENJAGA perilaku lama (barH proporsional 44/120) — tak berubah.
        val bigLabel = h >= 240
        // 30x20 = label "sedang": lebar 240 x tinggi 160 dot. Jalur generik di bawah ini
        // menaruh barcode proporsional 44/120 dan berhenti di dot 138 → 5-7 mm bawah kosong.
        // Layout A (30x20): nama 1,3 / harga 3,5 / barcode 8,4 / nomor 16,8 mm; margin bawah 1,4 mm.
        // Hanya 30x20 — 40x20 belum diukur.
        val medium = !bigLabel && w == 240 && h == 160
        // TERUKUR dari cetakan 30x20 (foto 382x290, 10,87 px/mm): printer mencetak 0,73x dari
        // angka perintah, seragam X & Y, dari origin (0,0). Bukti bebas-kalibrasi:
        //   barcode perintah x=31 (n=3, netral) -> tercetak gap kiri 1,1 mm, kanan 8,6 mm (tidak center)
        //   isi perintah sampai dot 160 -> tercetak 14,7 mm dari label 20 mm (sisa 5,3 mm kosong)
        // Jadi kanvas perintah utk label 30x20 = 30x20 mm / 0,73 = 329 x 219 dot. Semua koordinat
        // tetap DI DALAM kanvas SIZE (tidak ada y > h) — kanvasnya yang diperbesar, bukan bocor.
        // 50x25 = label "lebar": 400x200 dot. Sama seperti 30x20, printer mengecilkan ruang
        // koordinat 0,73x -> kanvas perintah 548x274. Desain B (harga x3 dominan + garis
        // pemisah harga/barcode), disetujui user.
        val wide = !bigLabel && w == 400 && h == 200
        // 25x15 = label terkecil (200x120 dot). Desain A: nama / harga x2 / barcode / nomor.
        val small = !bigLabel && w == 200 && h == 120
        // 40x20 = lebar 40 mm tapi pendek 20 mm. Kanvas 438x219, layout sama pola medium
        // (nama/harga/barcode/nomor) tapi harga boleh x3 karena lebar 438 dot.
        val wide20 = !bigLabel && w == 320 && h == 160
        // Satu sumber kebenaran: kanvas() dipakai juga oleh buatKotakUji(), jadi label
        // kalibrasi & label produk selalu berada di ruang koordinat yang sama.
        val (wC, hC) = kanvas(widthMm, heightMm)
        // Spasi teks(harga)↔barcode utk 40x30 = 0,5 cm (40 dot dr bawah harga). Grund gap6 sudah ada di yB,
        // jadi tambah 34 di sini. Ukuran lain (h<240) gap 0 (perilaku lama).
        val bigGap = if (bigLabel) 34 else 0
        val barY = yB + bigGap                       // posisi-y BARCODE ukuran lain (bigLabel pakai barY2 di C2)
        val barH = if (bigLabel)
            (h - barY - 18).coerceAtLeast(24)
        else
            (h * 44 / 120).coerceIn(16, (h - yB - 6).coerceAtLeast(16))
        // Nama label BESAR (40x30): font TSPL "1" lebar nyata ≈8 dot/char (terukur dr cetakan:
        // 23 char = 186 dot = 23.2 mm). Kalibrasi lama 13 dot/char (dari foto ber-skew) terlalu
        // besar -> nama dipotong 23 char padahal muat ~38 -> info terbuang & "..." muncul cepat.
        val nbNama = if (bigLabel) ((w - 16) / CW_BIG).coerceAtLeast(10) else 0
        // Jumlah char nama yg muat di 30x20 (font "1" x1, ~10 dot/char terukur).
        val nbNama30 = if (medium) ((MED_W - 24) / CW_BIG).coerceAtLeast(8) else 0
        // 50x25: kanvas 548 dot, font "1" ~10 dot/char.
        val nbNama50 = if (wide) ((WIDE_W - 24) / CW_BIG).coerceAtLeast(10) else 0
        // 25x15: area cetak 150 cmd -> 18 char font x1 (bukan kanvas 610).
        val nbNama25 = if (small) ((SMALL_AW - 4) / CW_BIG).coerceAtLeast(8) else 0
        // 40x20: kanvas 438 dot -> muat ~41 char font x1.
        val nbNama40x20 = if (wide20) ((W20_W - 24) / CW_BIG).coerceAtLeast(10) else 0
        // NOTE multi-label (v1.6.11): PER-LABEL job utuh — setiap label = SIZE h + GAP + CLS +
        // isi (TEXT/BARCODE, y TANPA offset, semua dalam kanvas) + PRINT 1,1 + FORFEED <h>.
        //   - per-label SIZE h = kanvas SATU label (aturan TSPL: SIZE = 1 kanvas; zona idx*step
        //     dalam SIZE setinggi n h VIOLATES dgn zona y>h terpotong -> cuma item 1, v1.6.10).
        //   - FORFEED <h> eksplisit = maju TEPAT SATU label (h dot) antar job -> tak bertimpa
        //     (v1.6.9 pakai FORFEED tanpa angka = feed default kurang -> timpa kalau >1 label).
        //   - konten per label dari `l` masing2 -> item beda tercetak beda.
        for (l in ls) {
            // Harga 40x30: pasang titik ribuan (Rp 50.000) apa pun bentuk input (& idempoten).
            // Harga 40x30 sudah dibersihkan & titik-ribuan dipasang ulang (sanitizeTsp asli membuang '.')
            val hRibu = if (bigLabel || wide || small || wide20) hargaTsp(l.harga, 24) else l.harga
            out.write("SIZE $wC,$hC$eol".toByteArray(Charsets.US_ASCII))
            // GAP = jarak fisik antar label (mm, dot). 16 mm itu SALAH untuk label 25x15:
            // pitch jadi 15+16=31 mm -> feed lari ke label berikutnya. Pakai gap pendek 2 mm
            // supaya pitch = tinggi label + 2.
            // small: GAP m,n -> n = offset jarak cetak (dot). Arahan user "tambah gap":
            // offset 10 cmd menggeser awal cetak 1 mm ke atas di kertas, tanpa mengubah
            // koordinat isi (origin tetap 0,0). Risiko: feed bisa lari ke label berikutnya.
            val gapArg = when {
                bigLabel -> "16,0"
                small -> "2,${SMALL_GAP_OFS}"
                else -> "2,0"
            }
            out.write("GAP $gapArg$eol".toByteArray(Charsets.US_ASCII))
            out.write("CLS$eol".toByteArray(Charsets.US_ASCII))
            if (barcode2d) {
                val (mQ, xQ) = qrGeo(l.bc, w, h, barY)
                val qrH = (h * 44 / 120).coerceIn(16, (h - barY - 6).coerceAtLeast(16))
                val bcSan = sanitizeTsp(l.bc)
                val xN2 = if (bigLabel) centerXTsP(clipTsp(l.nama, nbNama).length, fm, w, CW_BIG) else 4
                val xH2 = if (bigLabel) centerXTsP(hRibu.length, fm, w, CW_BIG) else 4
                out.write("TEXT $xN2,$yN,\"1\",0,$fm,$fm,\"${clipTsp(l.nama, if (bigLabel) nbNama else namaMaxChar(w, fm))}\"$eol".toByteArray(Charsets.US_ASCII))
                val txtH2 = hRibu
                out.write("TEXT $xH2,$yH,\"1\",0,$fm,$fm,\"$txtH2\"$eol".toByteArray(Charsets.US_ASCII))
                out.write("BARCODE $xQ,$barY,\"QRCODE\",$qrH,0,0,$mQ,\"$bcSan\"$eol".toByteArray(Charsets.US_ASCII))
            } else {
                val (bcX, bcN) = if (medium || wide || small || wide20) barcodeGeoCmd(l.bc, wC)
                                 else barcodeGeo(l.bc, w, if (bigLabel) BIG_BC_LEAD else 0)
                val hTxt = hRibu
                if (bigLabel) {
                    // ---- Desain C2 (40x30): nama 1 baris, HARGA FULL-WIDTH ×pm, garis, barcode, nomor.
                    // Harga dapat lebar penuh (bukan pane kanan) supaya nominal panjang tak menyusut
                    // ke ×1. pm = pengali terbesar yg muat; 120.000 -> ×3 (264 dot), 12.000.000 -> ×2.
                    val pm = (3 downTo 1).firstOrNull { hTxt.length * CW_BIG * it <= w - 16 } ?: 1
                    val fhP = CH_BIG * pm                     // tinggi glyph NYATA (bukan 12*pm)
                    // Nama TURUN: cetakan v1.8.4 nama mulai 0,24 mm dari tepi atas (mentok) —
                    // 14 cmd × 0,75 ≈ 1,7 mm cetak.
                    val yNb = yN + 14                          // 18
                    val yH2 = yNb + CH_BIG + 4                 // 42: di bawah nama
                    val barY2 = yH2 + fhP + 40                 // gap harga↔barcode 0,5 cm (aturan user)
                    // Semua konten WAJIB muat dalam kanvas SIZE (y ≤ h=240): koordinat di luar h
                    // belum terbukti tercetak di printer ini. 22 cmd = baris nomor barcode + marjin.
                    val barH2 = (h - barY2 - 22).coerceAtLeast(24)   // 76 (pm3) / 96 (pm2)
                    val yTxt2 = barY2 + barH2 + 2
                    val xN2 = centerXTsP(clipTsp(l.nama, nbNama).length, 1, w, CW_BIG)
                    val xH2 = centerXTsP(hTxt.length, pm, w, CW_BIG)
                    out.write("TEXT $xN2,$yNb,\"1\",0,1,1,\"${clipTsp(l.nama, nbNama)}\"$eol".toByteArray(Charsets.US_ASCII))
                    out.write("TEXT $xH2,$yH2,\"1\",0,$pm,$pm,\"$hTxt\"$eol".toByteArray(Charsets.US_ASCII))
                    // Garis pemisah harga↔barcode (BAR: x,y,lebar,tebal). y = bawah glyph harga + 6.
                    out.write("BAR 10,${yH2 + fhP + 18},300,2$eol".toByteArray(Charsets.US_ASCII))
                    out.write("BARCODE $bcX,$barY2,\"${barcodeKodeTsp(l.bc)}\",$barH2,0,0,$bcN,$bcN,\"${sanitizeTsp(l.bc)}\"$eol".toByteArray(Charsets.US_ASCII))
                    val txt = clipTsp(l.bc, (w / CW_BIG).coerceAtLeast(8))
                    out.write("TEXT ${centerXTsP(txt.length, 1, w, CW_BIG)},$yTxt2,\"1\",0,1,1,\"$txt\"$eol".toByteArray(Charsets.US_ASCII))
                } else if (wide20) {
                    // ---- 40x20 di kanvas 438x219. Pola sama dgn medium, harga boleh x3.
                    // Cetak: nama 1,28-3,10 / harga 3,47-8,94 (pm3) / barcode 9,86-16,24 /
                    // nomor 16,79-18,61, sisa 1,39 mm dari label 20 mm.
                    val pm = (3 downTo 1).firstOrNull { hTxt.length * CW_BIG * it <= wC - 24 } ?: 1
                    val fhP = CH_BIG * pm
                    val yNb = 14                                // cetak 1,28 mm
                    val yH2 = 38                                // cetak 3,47 mm
                    val barY2 = yH2 + fhP + 10                  // 108 (pm3) -> 9,86 mm
                    val yTxt2 = 184                             // cetak 16,79-18,61 mm
                    val barH2 = (yTxt2 - barY2 - 6).coerceAtLeast(40)  // 70 -> 6,39 mm
                    val namaT = clipTsp(l.nama, nbNama40x20)
                    val txt = clipTsp(l.bc, nbNama40x20)
                    out.write("TEXT ${centerXTsP(namaT.length, 1, wC, CW_BIG)},$yNb,\"1\",0,1,1,\"$namaT\"$eol".toByteArray(Charsets.US_ASCII))
                    out.write("TEXT ${centerXTsP(hTxt.length, pm, wC, CW_BIG)},$yH2,\"1\",0,$pm,$pm,\"$hTxt\"$eol".toByteArray(Charsets.US_ASCII))
                    out.write("BARCODE $bcX,$barY2,\"${barcodeKodeTsp(l.bc)}\",$barH2,0,0,$bcN,$bcN,\"${sanitizeTsp(l.bc)}\"$eol".toByteArray(Charsets.US_ASCII))
                    out.write("TEXT ${centerXTsP(txt.length, 1, wC, CW_BIG)},$yTxt2,\"1\",0,1,1,\"$txt\"$eol".toByteArray(Charsets.US_ASCII))
                } else if (small) {
                    // ---- Desain A utk AREA CETAK TERUKUR 150x80 cmd (15 x 8 mm) di x=200.
                    // Tinggi 80 cmd tidak cukup utk 4 baris (nama 20 + harga x2 40 + barcode
                    // 20 + nomor 20 = 100), jadi 3 baris: nama x1 / harga x2 / barcode.
                    // Cetak: nama 0,0-2,0 / harga 2,0-6,0 / barcode 6,0-8,0 mm.
                    // Geser KANVAS 10 cmd: origin TSPL selalu (0,0), jadi menggeser kanvas =
                    // mengurangi semua y sebesar 10. Nama sudah di y=0 -> tidak bisa ikut naik
                    // (y negatif tidak dicetak). Jadi kanvas digeser utk baris yang punya ruang:
                    // nama tetap, harga & barcode naik 1 mm dari posisi v1.8.25.
                    val pm = (2 downTo 1).firstOrNull { hTxt.length * CW_BIG * it <= SMALL_AW - 4 } ?: 1
                    val fhP = CH_BIG * pm                      // 40 (pm2) / 20 (pm1)
                    val yN = 0                                 // nama: tepi atas kanvas (mentok)
                    val yH2 = yN + CH_BIG                      // 20 -> 2,00 mm
                    val barY2 = yH2 + fhP                      // 60/40 -> 6,00 / 4,00 mm
                                                               // (jarak ke harga = 0, arahan user)
                    // Uji v1.8.38: height DIPATUHI printer. barH 60 cmd -> terukur 5 mm.
                    // barH 50 cmd -> ~4,2 mm; dipilih supaya masih muat area 12 mm.
                    // Arahan user: tinggi barcode 0,3. Ditafsirkan 0,3 cm = 3 mm = 30 cmd,
                    // karena 0,3 mm = 3 cmd tidak akan terbaca scanner mana pun.
                    val barH2 = 30
                    val namaT = clipTsp(l.nama, SMALL_AW / CW_BIG)
                    val xNama = SMALL_X0 + centerXTsP(namaT.length, 1, SMALL_AW, CW_BIG)
                    val xHarga = SMALL_X0 + centerXTsP(hTxt.length, pm, SMALL_AW, CW_BIG)
                    val (bcXa, bcNa) = barcodeGeoCmd(l.bc, SMALL_BW)
                    out.write("TEXT $xNama,$yN,\"1\",0,1,1,\"$namaT\"$eol".toByteArray(Charsets.US_ASCII))
                    out.write("TEXT $xHarga,$yH2,\"1\",0,$pm,$pm,\"$hTxt\"$eol".toByteArray(Charsets.US_ASCII))
                    out.write("BARCODE ${SMALL_X0 + bcXa},$barY2,\"${barcodeKodeTsp(l.bc)}\",$barH2,0,0,$bcNa,$bcNa,\"${sanitizeTsp(l.bc)}\"$eol".toByteArray(Charsets.US_ASCII))
                    // Penanda diagnostik: versi build + nilai barH yg benar-benar dikirim.
                    // Kalau di kertas tertulis angka lain, berarti APK yg dipasang bukan build ini.
                    val tagY = (barY2 + barH2 + 2).coerceAtMost(SMALL_Y0 + SMALL_AH - 10)
                    out.write("TEXT ${SMALL_X0 + 2},$tagY,\"1\",0,1,1,\"V1840-H$barH2\"$eol".toByteArray(Charsets.US_ASCII))
                } else if (wide) {
                    // ---- Desain B (50x25) di kanvas 548x274: nama, HARGA x3 dominan, garis
                    // pemisah, barcode, nomor. Cetak = cmd x 0,0913 mm.
                    // target: nama 1,3 / harga 4,0-9,5 / garis 10,8 / barcode 13,3-20,8 / nomor 21,4
                    val pm = (3 downTo 1).firstOrNull { hTxt.length * CW_BIG * it <= wC - 24 } ?: 1
                    val fhP = CH_BIG * pm                      // 60 (pm3) / 40 (pm2)
                    val yNb = 14                               // cetak 1,28 mm
                    val yH2 = 44                               // cetak 4,01 mm
                    // Garis pemisah 14 dot di bawah glyph harga (BAR: x,y,lebar,tebal).
                    val yLine = yH2 + fhP + 14                 // 118 (pm3) -> 10,77 mm
                    val barY2 = yLine + 28                     // 146 -> 13,32 mm
                    val barH2 = 82                             // cetak 7,48 mm
                    val yTxt2 = barY2 + barH2 + 6              // 234 -> 21,35 mm
                    val namaT = clipTsp(l.nama, nbNama50)
                    val txt = clipTsp(l.bc, nbNama50)
                    out.write("TEXT ${centerXTsP(namaT.length, 1, wC, CW_BIG)},$yNb,\"1\",0,1,1,\"$namaT\"$eol".toByteArray(Charsets.US_ASCII))
                    out.write("TEXT ${centerXTsP(hTxt.length, pm, wC, CW_BIG)},$yH2,\"1\",0,$pm,$pm,\"$hTxt\"$eol".toByteArray(Charsets.US_ASCII))
                    val barX = (wC - 448) / 2                  // 50 -> tepi garis 9,1 mm kiri/kanan
                    out.write("BAR $barX,$yLine,448,2$eol".toByteArray(Charsets.US_ASCII))
                    out.write("BARCODE $bcX,$barY2,\"${barcodeKodeTsp(l.bc)}\",$barH2,0,0,$bcN,$bcN,\"${sanitizeTsp(l.bc)}\"$eol".toByteArray(Charsets.US_ASCII))
                    out.write("TEXT ${centerXTsP(txt.length, 1, wC, CW_BIG)},$yTxt2,\"1\",0,1,1,\"$txt\"$eol".toByteArray(Charsets.US_ASCII))
                } else if (medium) {
                    // ---- Layout A (30x20) di kanvas perintah 329x219 (lihat wC/hC).
                    // Cetak (mm) = cmd x 0,0913, jadi target Layout A: nama 1,3 / harga 3,5 / barcode 8,4 /
                    // nomor 18,2, margin bawah 1,4 mm. pm = pengali harga terbesar yg muat.
                    val pm = (2 downTo 1).firstOrNull { hTxt.length * CW_BIG * it <= wC - 24 } ?: 1
                    val fhP = CH_BIG * pm
                    val yNb = 14                               // cetak 1,28 mm
                    val yH2 = yNb + CH_BIG + 4                 // 38 -> cetak 3,47 mm
                    val barY2 = yH2 + fhP + 14                 // 92 (pm2) / 72 (pm1) -> 8,40 / 6,57 mm
                    val barH2 = 80                             // cetak 7,3 mm (dulu 4,4 mm)
                    val yTxt2 = hC - CH_BIG - 15               // 184 -> cetak 16,8-18,6 mm, sisa 1,4 mm
                    val namaT = clipTsp(l.nama, nbNama30)
                    val txt = clipTsp(l.bc, nbNama30)
                    out.write("TEXT ${centerXTsP(namaT.length, 1, wC, CW_BIG)},$yNb,\"1\",0,1,1,\"$namaT\"$eol".toByteArray(Charsets.US_ASCII))
                    out.write("TEXT ${centerXTsP(hTxt.length, pm, wC, CW_BIG)},$yH2,\"1\",0,$pm,$pm,\"$hTxt\"$eol".toByteArray(Charsets.US_ASCII))
                    out.write("BARCODE $bcX,$barY2,\"${barcodeKodeTsp(l.bc)}\",$barH2,0,0,$bcN,$bcN,\"${sanitizeTsp(l.bc)}\"$eol".toByteArray(Charsets.US_ASCII))
                    out.write("TEXT ${centerXTsP(txt.length, 1, wC, CW_BIG)},$yTxt2,\"1\",0,1,1,\"$txt\"$eol".toByteArray(Charsets.US_ASCII))
                } else {
                    val xN = 4
                    val xH = 4
                    out.write("TEXT $xN,$yN,\"1\",0,$fm,$fm,\"${clipTsp(l.nama, namaMaxChar(w, fm))}\"$eol".toByteArray(Charsets.US_ASCII))
                    out.write("TEXT $xH,$yH,\"1\",0,$fm,$fm,\"$hTxt\"$eol".toByteArray(Charsets.US_ASCII))
                    out.write("BARCODE $bcX,$barY,\"${barcodeKodeTsp(l.bc)}\",$barH,0,0,$bcN,$bcN,\"${sanitizeTsp(l.bc)}\"$eol".toByteArray(Charsets.US_ASCII))
                }
            }
            out.write("PRINT 1,1$eol".toByteArray(Charsets.US_ASCII))
            // FORFEED hanya untuk 40x30 (kanvas = ukuran fisik, jalur bigLabel). Ukuran lain
            // memakai kanvas perintah yg lebih besar dari label -> FORFEED menambah feed dan
            // membuat 1x cetak menghabiskan >1 lembar. Terukur 25x15: SIZE 150 + GAP 16 +
            // FORFEED 150 = 46 mm = 3 lembar untuk 1 label.
            if (bigLabel) out.write("FORFEED $hC$eol".toByteArray(Charsets.US_ASCII))
        }
        return out.toByteArray()
    }

    /** Pilih tipe barcode TSPL: 13-digit (EAN-13) atau Code128 utk yg lain. */
    private fun barcodeKodeTsp(bc: String): String =
        if (bc.length == 13 && bc.all { it.isDigit() }) "EAN13" else "128"

    /**
     * Hitung [x, narrow] barcode TSPL utk SEMUA ukuran label.
     *
     * Aturan v1.8.4: `n` dipilih supaya QUIET ZONE 10 modul/sisi masih masuk anggaran lebar.
     * Sebelumnya `n` diambil dari lebar penuh (`w / total`), jadi simbol mengisi label sampai
     * tepi → quiet zone tinggal 2–4 modul → scanner tak bisa lock (kasus 40×30 8-digit v1.8.3).
     * Modul tercetak = `n × 0,73` dot (printer memampatkan geometri ~0,73×, terukur dari cetakan).
     * EAN-13 = 95 modul (konstanta lama 131 salah → narrow terlalu kecil, simbol 8,7 mm).
     * @return [x (center), narrow]
     */
    private fun barcodeGeo(bc: String, wDots: Int, lead: Int = 0): Pair<Int, Int> {
        val total = if (bc.length == 13 && bc.all { it.isDigit() }) Ean13.TOTAL_MODUL
        else (Code128.encodeCDigits(bc) ?: Code128.encodeBText(bc)).totalModul.coerceAtLeast(10)
        val quiet = 10
        // Lebar command = total×n; tercetak = total×n×0,73. Anggarkan quiet zone 2×10 modul.
        val n = (wDots / (TSPL_BC_SCALE * (total + 2 * quiet))).toInt().coerceIn(1, 5)
        // 40×30 (lead>0): lebar nyata terukur = total×n×0,755. Ukuran lain tetap 0,73 (perilaku
        // v1.8.4 yg sudah terverifikasi, jangan diubah tanpa cetakan baru).
        val skala = if (lead > 0) LAYOUT_SCALE else TSPL_BC_SCALE
        val bwReal = (total * n * skala).toInt()
        val x = ((wDots - bwReal) / 2 - lead).coerceAtLeast(0)
        return x to n
    }

    /**
     * Barcode utk kanvas PERINTAH (30x20, kanvas 329x219): printer mengecilkan semuanya 0,73x,
     * jadi lebar simbol dihitung dalam dot perintah (bukan dot cetak). x = tengah kanvas.
     */
    private fun barcodeGeoCmd(bc: String, wCmd: Int): Pair<Int, Int> {
        val total = if (bc.length == 13 && bc.all { it.isDigit() }) Ean13.TOTAL_MODUL
        else (Code128.encodeCDigits(bc) ?: Code128.encodeBText(bc)).totalModul.coerceAtLeast(10)
        val quiet = 10
        // Quiet zone dulu (>=10 modul), TAPI modul harus >= 0,25 mm = 2 dot cetak.
        // Di kanvas perintah (skala 0,73) itu berarti n >= ceil(0,25*8/0,73) = 3.
        // Tanpa batas bawah ini, label 25x15 memberi n=2 -> modul 0,182 mm = tak terbaca.
        val nQuiet = wCmd / (total + 2 * quiet)
        val nMin = ceil(0.25 * DOTS_PER_MM / PRINT_SCALE).toInt()     // 3 (modul >= 0,25 mm)
        val nMax = ((wCmd - 2) / total).coerceAtLeast(1)                          // simbol harus muat
        val n = nQuiet.coerceAtLeast(nMin).coerceAtMost(nMax).coerceIn(1, 5)
        val x = ((wCmd - total * n) / 2).coerceAtLeast(1)
        return x to n
    }

    /** Kanvas perintah (dot) utk ukuran label tertentu — SATU sumber kebenaran, dipakai
     *  buatRunTSPL & buatKotakUji supaya angka uji = angka cetak sebenarnya. */
    fun kanvas(widthMm: Int, heightMm: Int): Pair<Int, Int> {
        val w = (widthMm * DOTS_PER_MM).toInt()
        val h = (heightMm * DOTS_PER_MM).toInt()
        val big = h >= 240
        return when {
            big -> w to h                                                   // 40x30: 1:1 + lead
            w == 400 && h == 200 -> WIDE_W to WIDE_H                        // 50x25
            w == 240 && h == 160 -> MED_W to MED_H                          // 30x20
            w == 320 && h == 160 -> W20_W to W20_H                          // 40x20
            w == 200 && h == 120 -> SMALL_SIZE_W to SMALL_SIZE_H            // 25x15 (area 200..350, 0..80)
            else -> w to h
        }
    }

    /**
     * Uji BATAS KANAN cetak. Area 200..350 cmd dipakai sejak awal, tapi permintaan
     * memperlebar area utk barcode butuh tahu sampai x berapa printer benar-benar
     * mencetak. Label ini: satu bar panjang x=200..500 (tinggi 10 cmd) + tick tiap
     * 50 cmd dgn label angkanya. Ukur/lihat di mana bar berhenti -> itulah batas nyata.
     */
    fun buatUjiBatasKanan(widthMm: Int, heightMm: Int): ByteArray {
        val (wC, hC) = kanvas(widthMm, heightMm)
        val out = ByteArrayOutputStream()
        val eol = "\r\n"
        fun bar(x: Int, y: Int, w: Int, h: Int) {
            out.write("BAR $x,$y,$w,$h$eol".toByteArray(Charsets.US_ASCII))
        }
        fun txt(x: Int, y: Int, t: String) {
            out.write("TEXT $x,$y,\"1\",0,1,1,\"$t\"$eol".toByteArray(Charsets.US_ASCII))
        }
        out.write("SIZE $wC,$hC$eol".toByteArray(Charsets.US_ASCII))
        out.write("GAP 2,0$eol".toByteArray(Charsets.US_ASCII))
        out.write("CLS$eol".toByteArray(Charsets.US_ASCII))
        // bar utama 200..500 (30 mm) tinggi 10 cmd
        bar(200, 6, 300, 10)
        // tick + label tiap 50 cmd: 250 300 350 400 450
        var x = 250
        while (x <= 450) {
            bar(x, 20, 2, 16)
            txt(x - 8, 40, "$x")
            x += 50
        }
        // penanda awal area yg sudah terbukti (200 & 350)
        bar(200, 60, 2, 20); txt(196, 84, "200")
        bar(350, 60, 2, 20); txt(346, 84, "350")
        txt(200, 108, "UKUR SAMPAI MANA BAR")
        out.write("PRINT 1,1$eol".toByteArray(Charsets.US_ASCII))
        return out.toByteArray()
    }

    /**
     * Uji TINGGI vs ROTASI barcode. Satu label, tiga variasi isi sama:
     *   H20 : barH 20, rot 0  -> kondisi produksi sekarang (tinggi ~2 mm)
     *   H60 : barH 60, rot 0  -> apakah parameter height dipatuhi
     *   R90 : barH 40, rot 1  -> bar mendatar, "panjang" jadi lebih besar
     * Baca: H60 = H20 -> height diabaikan. R90 mendatar -> rotasi dipatuhi.
     */
    fun buatUjiTinggiRotasi(bc: String, widthMm: Int, heightMm: Int): ByteArray {
        val (wC, hC) = kanvas(widthMm, heightMm)
        val out = ByteArrayOutputStream()
        val eol = "\r\n"
        val kode = barcodeKodeTsp(bc)
        val isi = sanitizeTsp(bc)
        out.write("SIZE $wC,$hC$eol".toByteArray(Charsets.US_ASCII))
        out.write("GAP 2,0$eol".toByteArray(Charsets.US_ASCII))
        out.write("CLS$eol".toByteArray(Charsets.US_ASCII))
        fun txt(x: Int, y: Int, t: String) {
            out.write("TEXT $x,$y,\"1\",0,1,1,\"$t\"$eol".toByteArray(Charsets.US_ASCII))
        }
        fun bcCmd(x: Int, y: Int, h: Int, rot: Int) {
            out.write("BARCODE $x,$y,\"$kode\",$h,0,$rot,1,1,\"$isi\"$eol".toByteArray(Charsets.US_ASCII))
        }
        txt(SMALL_X0, 0, "H20")
        bcCmd(235, 18, 20, 0)
        txt(SMALL_X0, 45, "H60")
        bcCmd(235, 63, 60, 0)
        txt(SMALL_X0, 130, "R90")
        bcCmd(SMALL_X0, 148, 40, 1)
        out.write("PRINT 1,1$eol".toByteArray(Charsets.US_ASCII))
        return out.toByteArray()
    }

    /**
     * Perintah DETEKSI GAP / panjang label. Dipakai kalau 1x PRINT menghabiskan >1 lembar:
     * artinya printer belum tahu pitch label (dia feed ke posisi gap yg salah).
     * TSPL: `GAPDETECT` = ukur jarak gap; `SET GAP` = setel sensor gap; `AUTODETECT` cadangan.
     * Tidak mengubah layout apa pun — hanya menyuruh printer mengukur kertasnya sendiri.
     */
    fun buatGapDetect(): ByteArray {
        val eol = "\r\n"
        return ("SET GAP 16,0$eol" + "GAPDETECT$eol" + "AUTODETECT$eol").toByteArray(Charsets.US_ASCII)
    }

    /**
     * KOTAK UJI 1 cm x 1 cm di pojok kiri-atas kanvas. Satu-satunya isi label, sengaja tanpa
     * teks: supaya yang diukur cuma kotak. 1 cm pada skala 0,80 = 100 cmd (1 cmd = 0,1 mm).
     * Cetak -> ukur sisi kotak + jarak ke tepi label -> arah koreksi ditentukan dari situ.
     */
    fun buatKotakUji(widthMm: Int, heightMm: Int): ByteArray {
        val (wC, hC) = kanvas(widthMm, heightMm)
        // Arahan user (dihitung dari sudut a): geser ke KIRI 2,1 cm = 210 cmd (1 cmd = 0,1 mm).
        // Kanvas diperlebar supaya x offset + kotak masih di dalam SIZE (kalau x > SIZE, dipotong).
        val geserX = 360
        val lebarKanvas = wC + geserX
        val out = ByteArrayOutputStream()
        val eol = "\r\n"
        fun bar(x: Int, y: Int, w: Int, h: Int) {
            out.write("BAR $x,$y,$w,$h$eol".toByteArray(Charsets.US_ASCII))
        }
        out.write("SIZE $lebarKanvas,$hC$eol".toByteArray(Charsets.US_ASCII))
        out.write("GAP 2,0$eol".toByteArray(Charsets.US_ASCII))
        out.write("CLS$eol".toByteArray(Charsets.US_ASCII))
        val sisi = 100          // 1 cm pada 0,1 mm/cmd
        val t = 3               // tebal garis, biar kebaca
        // Arahan (b tetap):
        //   a geser kiri 0,2 + 0,3 = 0,5 cm -> 50 cmd; kotak melebar 50 cmd ke kiri.
        //   c geser ke ATAS 0,2 cm -> 20 cmd: sisi bawah naik, kotak jadi lebih pendek.
        val geserA = 50         // total geseran kiri sudut a (cmd)
        val naikC = 20          // sudut c naik (cmd)
        val lebar = sisi + geserA           // 150
        val tinggi = sisi - naikC           // 80
        // Riwayat arahan x: 360 (kanan 3,6 cm) - 210 (kiri 2,1 cm dari a) + 100 (kanan 1 cm
        // dari b) - 50 (a kiri 0,5 cm) = 200 cmd.
        val x0 = geserX - 210 + 100 - geserA
        // Arahan y: naik 0,2 cm = 20 cmd. y=0 sudah tepi atas kanvas, jadi nilai negatif
        // tidak bisa dicetak TSPL -> dipakai 0 (batas atas), sisanya perlu geser lewat GAP.
        val y0 = (0 - 20).coerceAtLeast(0)
        bar(x0, y0, lebar, t)                  // atas
        bar(x0, y0 + tinggi - t, lebar, t)           // bawah
        bar(x0, y0, t, tinggi)                  // kiri
        bar(x0 + lebar - t, y0, t, tinggi)       // kanan
        // Huruf kecil (font "1" x1 = ~8x20 cmd = ~0,8 x 2 mm) di tiap sudut, di DALAM kotak:
        // a=kiri-atas, b=kanan-atas, c=kiri-bawah, d=kanan-bawah. Patokan arah koreksi.
        fun txt(x: Int, y: Int, v: String) {
            out.write("TEXT $x,$y,\"1\",0,1,1,\"$v\"$eol".toByteArray(Charsets.US_ASCII))
        }
        txt(x0 + t + 6, y0 + t + 4, "a")
        txt(x0 + lebar - 22, y0 + t + 4, "b")
        txt(x0 + t + 6, y0 + tinggi - 26, "c")
        txt(x0 + lebar - 22, y0 + tinggi - 26, "d")
        out.write("PRINT 1,1$eol".toByteArray(Charsets.US_ASCII))
        return out.toByteArray()
    }

    /**
     * QR: pilih ukuran module (dot) supaya QR kotak muat label & TEBAL (terbaca).
     * Perkiraan QR value: ~29 modul utk data sampai ~40 alphanumeric (QR v3-4), batas aman.
     * Menyesuaikan top area (tinggi tersedia). @return [module, x-tengah].
     */
    private fun qrGeo(bc: String, w: Int, h: Int, topY: Int): Pair<Int, Int> {
        val modul = 29
        val avail = (h - topY).coerceAtLeast(30)
        val m = (minOf(w, avail) / modul).coerceIn(2, 10)
        val size = modul * m
        val x = ((w - size) / 2).coerceAtLeast(1)
        return m to x
    }

    /** QR (preview/screen): sama dgn qrGeo print — module & x-tengah. */
    private fun previewQrGeo(bc: String, w: Int, h: Int, topY: Int): Pair<Int, Int> = qrGeo(bc, w, h, topY)

    /** x-center utk TEXT TSPL font "1": lebar nyata ≈8 dot/char (terukur dr cetakan 40x30 —
     *  23 char = 186 dot). Konstanta lama 4 dot/char bikin teks bergeser KANAN & mentok tepi. */
    private fun centerXTsP(len: Int, mul: Int, w: Int, cw: Int = 8): Int =
        ((w - len * cw * mul) / 2).coerceAtLeast(1)

    /** Buang karakter yg bisa merusak perintah TSPL. */
    private fun sanitizeTsp(s: String): String = s.filter { it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z' || it == ' ' }

    /** Clip teks agar tak melebihi lebar label (est. per-char utk font multiplier2). Kepanjangan
     *  dipotong 1-baris + disisipi "..." (marker lihat terpotong; TSPL font "1" = ASCII aman). */
    private fun clipTsp(s: String, maxChars: Int): String {
        val clean = sanitizeTsp(s)
        if (clean.length <= maxChars) return clean
        val mark = "..."
        return if (maxChars > mark.length) clean.take(maxChars - mark.length) + mark
        else clean.take(maxChars)
    }

    /**
     * Teks HARGA utk TSPL: dibersihkan dulu dari karakter berbahaya (sanitizeTsp buang '.'),
     * lalu titik-ribuan dipasang ULANG sebelum dicetak. Jadi "Rp 50.000" tidak jadi "Rp 50000".
     */
    private fun hargaTsp(s: String, maxChars: Int = 24): String {
        val clean = sanitizeTsp(s)
        val dig = clean.filter { it.isDigit() }
        val base = if (dig.isEmpty()) clean else try {
            val n = dig.toLong()
            "Rp " + String.format("%,d", n).replace(',', '.')
        } catch (_: Exception) { clean }
        return if (base.length > maxChars) base.take(maxChars - 1) + "." else base
    }

    /** Max chars NAMA di label agar muat 1 baris (TSPL font "1" ≈ 4 dot/char × fontMul),
     *  dipotong biar tak overflow lebar label; barcode tetap utamakan (diletak di baris lain).
     *  Cap 16 utk label 25mm (identik perilaku lama); label lebih lebar boleh lebih panjang (<=48). */
    private fun namaMaxChar(w: Int, fontMul: Int): Int {
        val cap = if (w <= 200) 16 else 48
        return minOf(cap, ((w - 8) / (4.0 * fontMul)).toInt()).coerceAtLeast(4)
    }
}
