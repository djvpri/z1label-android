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
        val namaClipped = clipText(nama, paint, w - (sc * 2))
        c.drawText(namaClipped, w / 2f, sc * 3f, paint)

        // --- harga (tipis, agak besar) ---
        paint.textSize = sc * 3.2f
        c.drawText(hargaShow, w / 2f, sc * 7.4f, paint)

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
        val hsl = if (h >= 240) ribuIdn(harga) else harga
        c.drawText(hsl, w / 2f, sc * 7.4f, paint)

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
            val bc = Code128.encodeCDigits(barcode) ?: Code128.encodeBText(barcode)
            // Label 40x30 (h>=240): barcode penuh mengisi sisa + nomor barcode di bawah (mirror TSPL).
            val bigL = h >= 240
            val hpx = if (bigL) ((h - topBar - 18) * 9 / 10) else h - topBar
            drawBarcode(c, bc, hpx, h, sc, w)
            if (bigL) {
                val txt = clipText(barcode, paint, w - (sc * 2))
                paint.textSize = sc * 0.9f
                c.drawText(txt, w / 2f, topBar + hpx + (sc * 1.4f), paint)
            }
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
        // Gap teks↔barcode utk 40x30 = 0,5 cm (5 mm = 40 dot). Ukuran lain (h<240) gap 0 (perilaku lama).
        val bigGap = if (bigLabel) 40 else 0
        val barY = yB + bigGap                       // posisi-y BARCODE (di bawah gap besar)
        val barH = if (bigLabel)
            // dari barY, sisakan ~18 dot utk nomor barcode & marjin bawah (anti keluar kanvas)
            (h - barY - 18).coerceAtLeast(24)
        else
            (h * 44 / 120).coerceIn(16, (h - yB - 6).coerceAtLeast(16))
        val yBcText = if (bigLabel) barY + barH + 2 else -1
        // NOTE multi-label (v1.6.11): PER-LABEL job utuh — setiap label = SIZE h + GAP + CLS +
        // isi (TEXT/BARCODE, y TANPA offset, semua dalam kanvas) + PRINT 1,1 + FORFEED <h>.
        //   - per-label SIZE h = kanvas SATU label (aturan TSPL: SIZE = 1 kanvas; zona idx*step
        //     dalam SIZE setinggi n h VIOLATES dgn zona y>h terpotong -> cuma item 1, v1.6.10).
        //   - FORFEED <h> eksplisit = maju TEPAT SATU label (h dot) antar job -> tak bertimpa
        //     (v1.6.9 pakai FORFEED tanpa angka = feed default kurang -> timpa kalau >1 label).
        //   - konten per label dari `l` masing2 -> item beda tercetak beda.
        for (l in ls) {
            // Harga 40x30: pasang titik ribuan (Rp 50.000) apa pun bentuk input (& idempoten).
            val hRibu = if (bigLabel) ribuIdn(l.harga) else l.harga
            out.write("SIZE $w,$h$eol".toByteArray(Charsets.US_ASCII))
            out.write("GAP 16,0$eol".toByteArray(Charsets.US_ASCII))
            out.write("CLS$eol".toByteArray(Charsets.US_ASCII))
            if (barcode2d) {
                val (mQ, xQ) = qrGeo(l.bc, w, h, barY)
                val qrH = (h * 44 / 120).coerceIn(16, (h - barY - 6).coerceAtLeast(16))
                val bcSan = sanitizeTsp(l.bc)
                val xN2 = if (bigLabel) centerXTsP(clipTsp(l.nama, namaMaxChar(w, fm)).length, fm, w) else 4
                val xH2 = if (bigLabel) centerXTsP(clipTsp(hRibu, 24).length, fm, w) else 4
                out.write("TEXT $xN2,$yN,\"1\",0,$fm,$fm,\"${clipTsp(l.nama, namaMaxChar(w, fm))}\"$eol".toByteArray(Charsets.US_ASCII))
                val txtH2 = clipTsp(hRibu, 24)
                out.write("TEXT $xH2,$yH,\"1\",0,$fm,$fm,\"$txtH2\"$eol".toByteArray(Charsets.US_ASCII))
                out.write("BARCODE $xQ,$barY,\"QRCODE\",$qrH,0,0,$mQ,\"$bcSan\"$eol".toByteArray(Charsets.US_ASCII))
            } else {
                // bigLabel (40x30): geser barcode ke KANAN (habiskan ruang kosong kanan), sisakan marjin kecil.
                val (bcX, bcN) = if (bigLabel) barcodeGeoBigR(l.bc, w) else barcodeGeo(l.bc, w)
                // label: nama (1 baris) + harga + barcode — sisanya dihilangkan
                val xN = if (bigLabel) centerXTsP(clipTsp(l.nama, namaMaxChar(w, fm)).length, fm, w) else 4
                val hTxt = clipTsp(hRibu, 24)
                val xH = if (bigLabel) centerXTsP(hTxt.length, fm, w) else 4
                out.write("TEXT $xN,$yN,\"1\",0,$fm,$fm,\"${clipTsp(l.nama, namaMaxChar(w, fm))}\"$eol".toByteArray(Charsets.US_ASCII))
                out.write("TEXT $xH,$yH,\"1\",0,$fm,$fm,\"$hTxt\"$eol".toByteArray(Charsets.US_ASCII))
                out.write("BARCODE $bcX,$barY,\"${barcodeKodeTsp(l.bc)}\",$barH,0,0,$bcN,$bcN,\"${sanitizeTsp(l.bc)}\"$eol".toByteArray(Charsets.US_ASCII))
                if (bigLabel) {
                    // Nomor barcode di bawah garis (label besar 40x30) biar terbaca & label penuh.
                    // Font "1" mul 1: teks center, font ≈4 dot/char.
                    val txt = clipTsp(l.bc, (w / 4).coerceAtLeast(8))
                    val xTxt = ((w - txt.length * 4) / 2).coerceAtLeast(0)
                    out.write("TEXT $xTxt,$yBcText,\"1\",0,1,1,\"$txt\"$eol".toByteArray(Charsets.US_ASCII))
                }
            }
            out.write("PRINT 1,1$eol".toByteArray(Charsets.US_ASCII))
            out.write("FORFEED $h$eol".toByteArray(Charsets.US_ASCII))   // maju tepat 1 label
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
     * Label 40x30 (bigLabel): barcode di-GESER KE KANAN (isi ruang kosong kanan), marjin kanan kecil.
     * EAN-13 lebarnya 95 modul (bukan 131) — jd simbol ~24mm & masih ada ruang utk geser lebih ke kanan.
     * @return [x (right-ish), narrow]
     */
    private fun barcodeGeoBigR(bc: String, w: Int): Pair<Int, Int> {
        val (bw, n) = if (bc.length == 13 && bc.all { it.isDigit() }) {
            val n = 2                      // 2 dot/modul (24 mm utk EAN-13 di 40mm), pas & terbaca
            (Ean13.TOTAL_MODUL * n) to n
        } else {
            val total = (Code128.encodeCDigits(bc) ?: Code128.encodeBText(bc)).totalModul.coerceAtLeast(10)
            val n = ((w * 9 / 10 - 4) / total).coerceIn(1, 4)
            (total * n) to n
        }
        // margin kanan sisakan ~6 dot (≈0.75 mm): barcode berhenti pas sebelum tepi.
        val x = if (bw > w - 2) 0 else (w - bw - 6).coerceAtLeast(0)
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

    /** x-center utk TEXT TSPL font "1" (lebar ≈4 dot/char × mul). Label besar (40x30) teks tengah. */
    private fun centerXTsP(len: Int, mul: Int, w: Int): Int =
        ((w - len * 4 * mul) / 2).coerceAtLeast(1)

    /** Buang karakter yg bisa merusak perintah TSPL. */
    private fun sanitizeTsp(s: String): String = s.filter { it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z' || it == ' ' }

    /** Clip teks agar tak melebihi lebar label (est. per-char utk font multiplier2). */
    private fun clipTsp(s: String, maxChars: Int): String {
        val clean = sanitizeTsp(s)
        return if (clean.length > maxChars) clean.take(maxChars - 1) + "." else clean
    }

    /** Max chars NAMA di label agar muat 1 baris (TSPL font "1" ≈ 4 dot/char × fontMul),
     *  dipotong biar tak overflow lebar label; barcode tetap utamakan (diletak di baris lain).
     *  Cap 16 utk label 25mm (identik perilaku lama); label lebih lebar boleh lebih panjang (<=48). */
    private fun namaMaxChar(w: Int, fontMul: Int): Int {
        val cap = if (w <= 200) 16 else 48
        return minOf(cap, ((w - 8) / (4.0 * fontMul)).toInt()).coerceAtLeast(4)
    }
}
