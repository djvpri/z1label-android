package com.zpos.label.escpos

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.zpos.label.bc.Code128
import com.zpos.label.bc.Ean13
import java.io.ByteArrayOutputStream

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

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        val sc = w / 25f // skala per-mm dalam dot

        // --- nama (kecil, tipis) ---
        paint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        paint.textSize = sc * 2.2f
        paint.textAlign = Paint.Align.CENTER
        val namaClipped = clipText(nama, paint, w - (sc * 2))
        c.drawText(namaClipped, w / 2f, sc * 3f, paint)

        // --- harga (tipis, agak besar) ---
        paint.textSize = sc * 3.2f
        c.drawText(harga, w / 2f, sc * 7.4f, paint)

        // --- barcode (isi ruang tersisa, kiri-kanan) ---
        val bc = Code128.encodeCDigits(barcode) ?: Code128.encodeBText(barcode)
        val heightPx = (h - (sc * 8.4f)).toInt().coerceAtLeast(30) // ruang bawah harga
        drawBarcode(c, bc, heightPx, h, sc, w)

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
        paint.textSize = sc * 2.2f
        c.drawText(clipText(nama, paint, w - (sc * 2)), w / 2f, sc * 3f, paint)
        paint.textSize = sc * 3.2f
        c.drawText(harga, w / 2f, sc * 7.4f, paint)

        val topBar = (h - (sc * 8.4f)).toInt().coerceAtLeast(30)
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
            val hpx = (h - topBar - (sc * 1f).toInt()).coerceAtLeast(20)
            for (b in Ean13.encodeBars(barcode)) {
                val xa = x0 + b.xModul * ndot
                c.drawRect(xa.toFloat(), topBar.toFloat(), (xa + b.wModul * ndot).toFloat(), (topBar + hpx).toFloat(), bcPaint)
            }
        } else {
            drawBarcode(c, Code128.encodeCDigits(barcode) ?: Code128.encodeBText(barcode), h - topBar, h, sc, w)
        }
        return bmp
    }

    private fun drawBarcode(
        c: Canvas,
        bc: Code128.Encoded,
        heightPx: Int,
        labelH: Int,
        sc: Float,
        w: Int
    ) {
        val totalDot = (bc.totalModul + Code128.QUIET * 2) * 2 // modul=2 dot minimal
        val scale = (w - (sc * 2)).coerceAtLeast(1f).toFloat() / totalDot.toFloat()
        val leftPad = ((w - (bc.totalModul + Code128.QUIET * 2) * 2 * scale) / 2f)
        val top = labelH - heightPx - (sc * 1f).toInt()
        val paint = Paint().apply { color = Color.BLACK }
        for (bar in bc.bars) {
            val x = leftPad + (bar.xModul + Code128.QUIET) * 2 * scale
            val ww = (bar.wModul * 2 * scale).coerceAtLeast(2f)
            c.drawRect(x, top.toFloat(), x + ww, (top + heightPx).toFloat(), paint)
        }
    }

    private fun clipText(t: String, p: Paint, maxW: Float): String {
        if (p.measureText(t) <= maxW) return t
        var s = t
        while (s.isNotEmpty() && p.measureText(s) > maxW) s = s.dropLast(1)
        return s.takeIf { it.isNotEmpty() } ?: "."
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
        val barH = (h - yB - 6).coerceIn(16, 44)
        // NOTE multi-label (v1.6.10): pakai SATU kanvas SETINGGI N label (SIZE setinggi step*n),
        // zona tiap label di-offset idx*step, lalu PRINT 1,1 + FORFEED per batch.
        //   - SIZE cukup tinggi => SEMUA zona masuk kanvas (konten item beda tercetak beda;
        //     perbaiki v1.6.8 yg SIZE tetap 1-label -> zona ke-2+ keluar -> salinan item 1).
        //   - PRINT 1,1 + FORFEED => printer maju setinggi kanvas utuh (tak bertimpa; perbaiki
        //     v1.6.9 yg per-label PRINT 1,1+FORFEED kosong -> feed antar-job kurang -> timpa).
        // GAP 16,0 = gap fisik antar label (kertas ber-gap). Zona label berikutnya = h + gap_gap.
        val GAP_DOT = 16
        val step = h + GAP_DOT                 // jarak antar label dalam kanvas (dot)
        val MAX_SIZE_H = 2048                  // batas tinggi kanvas TSPL (aman utk clabel dll)
        val chunkSize = (MAX_SIZE_H / step).coerceAtLeast(1)
        for (start in ls.indices step chunkSize) {
            val batch = ls.subList(start, minOf(start + chunkSize, ls.size))
            val n = batch.size
            val sizeH = step * n               // kanvas tinggi → semua zona masuk & feed pas
            out.write("SIZE $w,$sizeH$eol".toByteArray(Charsets.US_ASCII))
            out.write("GAP 16,0$eol".toByteArray(Charsets.US_ASCII))
            out.write("CLS$eol".toByteArray(Charsets.US_ASCII))
            batch.forEachIndexed { idx, l ->
                val off = idx * step           // awal zona label idx
                val yyN = yN + off
                val yyH = yH + off
                val yyB = yB + off
                if (barcode2d) {
                    val (mQ, xQ) = qrGeo(l.bc, w, h, yB)
                    val qrH = (h - yB - 6).coerceIn(16, 44)
                    val bcSan = sanitizeTsp(l.bc)
                    out.write("TEXT 4,$yyN,\"1\",0,$fm,$fm,\"${clipTsp(l.nama, namaMaxChar(w, fm))}\"$eol".toByteArray(Charsets.US_ASCII))
                    out.write("TEXT 4,$yyH,\"1\",0,$fm,$fm,\"${clipTsp(l.harga, 24)}\"$eol".toByteArray(Charsets.US_ASCII))
                    out.write("BARCODE $xQ,$yyB,\"QRCODE\",$qrH,0,0,$mQ,\"$bcSan\"$eol".toByteArray(Charsets.US_ASCII))
                } else {
                    val (bcX, bcN) = barcodeGeo(l.bc, w)
                    // label: nama (1 baris) + harga + barcode — sisanya dihilangkan
                    out.write("TEXT 4,$yyN,\"1\",0,$fm,$fm,\"${clipTsp(l.nama, namaMaxChar(w, fm))}\"$eol".toByteArray(Charsets.US_ASCII))
                    out.write("TEXT 4,$yyH,\"1\",0,$fm,$fm,\"${clipTsp(l.harga, 24)}\"$eol".toByteArray(Charsets.US_ASCII))
                    out.write("BARCODE $bcX,$yyB,\"${barcodeKodeTsp(l.bc)}\",$barH,0,0,$bcN,$bcN,\"${sanitizeTsp(l.bc)}\"$eol".toByteArray(Charsets.US_ASCII))
                }
            }
            out.write("PRINT 1,1$eol".toByteArray(Charsets.US_ASCII))
            out.write("FORFEED$eol".toByteArray(Charsets.US_ASCII))
        }
        return out.toByteArray()
    }

    /** Pilih tipe barcode TSPL: 13-digit (EAN-13) atau Code128 utk yg lain. */
    private fun barcodeKodeTsp(bc: String): String =
        if (bc.length == 13 && bc.all { it.isDigit() }) "EAN13" else "128"

    /**
     * Hitung posisi-x & narrow barcode SUPAYA MENYESUAIKAN lebar label (w dot) yang dipilih user.
     * EAN-13: lebar = 131 module×narrow; pilih narrow terbesar agar muat (≥1), lalu tengahkan.
     * Code128 (6-digit internal): narrow=2 tebal, fit di label >= 76 dot; x default.
     * @return [x, narrow] utk BARCODE TSPL.
     */
    private fun barcodeGeo(bc: String, wDots: Int): Pair<Int, Int> {
        if (bc.length == 13 && bc.all { it.isDigit() }) {
            val n = (wDots / 131).coerceAtLeast(1)
            val bw = 131 * n
            val x = ((wDots - bw) / 2).coerceAtLeast(1)
            return x to n
        }
        // Code128 (barcode bebas panjang): hitung LEBAR NYATA dr encoder supaya muat label.
        // Sebelumnya hardcode narrow=2 -> barcode panjang (8+ digit) overlebar -> terpotong/
        // tak muncul. Kini narrow = w / totalModul (>=1) sehingga sebanyak-banyaknya masuk.
        val total = (Code128.encodeCDigits(bc) ?: Code128.encodeBText(bc)).totalModul
            .coerceAtLeast(10)   // guard: totalModul 0
        val n = ((wDots - 4) / total).coerceIn(1, 4)   // utk pendek tetap tebal (<=4)
        val bw = total * n
        val x = ((wDots - bw) / 2).coerceAtLeast(1)
        return x to n
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

    /** Buang karakter yg bisa merusak perintah TSPL. */
    private fun sanitizeTsp(s: String): String = s.filter { it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z' || it == ' ' }

    /** Clip teks agar tak melebihi lebar label (est. per-char utk font multiplier2). */
    private fun clipTsp(s: String, maxChars: Int): String {
        val clean = sanitizeTsp(s)
        return if (clean.length > maxChars) clean.take(maxChars - 1) + "." else clean
    }

    /** Max chars NAMA di label agar muat 1 baris (TSPL font "1" ≈ 4 dot/char × fontMul),
     *  dipotong biar tak overflow lebar label; barcode tetap utamakan (diletak di baris lain). */
    private fun namaMaxChar(w: Int, fontMul: Int): Int =
        minOf(16, ((w - 8) / (4.0 * fontMul)).toInt()).coerceAtLeast(4)
}
