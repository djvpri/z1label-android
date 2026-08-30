package com.zpos.label.escpos

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.zpos.label.bc.Code128
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
        fontMul: Int = 1
    ): ByteArray {
        val w = (widthMm * DOTS_PER_MM).toInt()   // 200
        val h = (heightMm * DOTS_PER_MM).toInt()  // 120
        val out = ByteArrayOutputStream()
        val eol = "\r\n"
        out.write("SIZE $w,$h$eol".toByteArray(Charsets.US_ASCII))
        out.write("GAP 16,0$eol".toByteArray(Charsets.US_ASCII))
        out.write("CLS$eol".toByteArray(Charsets.US_ASCII))
        for (l in ls) {
            if (includeNama) {
                // label normal: nama + harga seragam (font 1, multiplier fontMul)
                out.write("TEXT 4,4,\"1\",0,$fontMul,$fontMul,\"${clipTsp(l.nama, 16)}\"$eol".toByteArray(Charsets.US_ASCII))
                out.write("TEXT 4,26,\"1\",0,$fontMul,$fontMul,\"${clipTsp(l.harga, 24)}\"$eol".toByteArray(Charsets.US_ASCII))
                out.write("BARCODE 8,48,\"${barcodeKodeTsp(l.bc)}\",54,0,0,2,2,\"${sanitizeTsp(l.bc)}\"$eol".toByteArray(Charsets.US_ASCII))
            } else {
                // label kecil: harga (seragam fontMul) + barcode (mengisi bawah)
                out.write("TEXT 4,4,\"1\",0,$fontMul,$fontMul,\"${clipTsp(l.harga, 28)}\"$eol".toByteArray(Charsets.US_ASCII))
                out.write("BARCODE 8,30,\"${barcodeKodeTsp(l.bc)}\",76,0,0,2,2,\"${sanitizeTsp(l.bc)}\"$eol".toByteArray(Charsets.US_ASCII))
            }
            out.write("PRINT 1,1$eol".toByteArray(Charsets.US_ASCII))
            out.write("FORFEED$eol".toByteArray(Charsets.US_ASCII))
        }
        return out.toByteArray()
    }

    /** Pilih tipe barcode TSPL: 13-digit (EAN-13, ~113 module sempit) atau Code128 utk lain. */
    private fun barcodeKodeTsp(bc: String): String =
        if (bc.length == 13 && bc.all { it.isDigit() }) "EAN13" else "128"

    /** Buang karakter yg bisa merusak perintah TSPL. */
    private fun sanitizeTsp(s: String): String = s.filter { it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z' || it == ' ' }

    /** Clip teks agar tak melebihi lebar label (est. per-char utk font multiplier2). */
    private fun clipTsp(s: String, maxChars: Int): String {
        val clean = sanitizeTsp(s)
        return if (clean.length > maxChars) clean.take(maxChars - 1) + "." else clean
    }
}
