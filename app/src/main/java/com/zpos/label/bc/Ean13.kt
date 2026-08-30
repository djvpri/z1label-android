package com.zpos.label.bc

/**
 * Encoder EAN-13 (standar Eurocode/GS1). Output daftar bar hitam memakai
 * satuan "modul" (bar narrow = 1 modul) sehingga bisa diskalakan penuh ke
 * lebar label (bitmap), bukan Cuma narrow=1 yg ~47% di label 25mm.
 */
object Ean13 {

    // 7-module pattern per digit; 1 = bar (black), 0 = gap (white)
    private val L = arrayOf(
        "0001101", "0011001", "0010011", "0111101", "0100011",
        "0110001", "0101111", "0111011", "0110111", "0001011"
    )
    private val R = arrayOf(
        "1110010", "1100110", "1101100", "1000010", "1011100",
        "1001110", "1010000", "1000100", "1001000", "1110100"
    )
    private val G = arrayOf(
        "0100111", "0110011", "0011011", "0100001", "0011101",
        "0111001", "0000101", "0010001", "0001001", "0010111"
    )
    // parity utk 12-digit data berdasarkan digit-pertama (1 => pakai G pattern)
    private val PARITY = arrayOf(
        "LLLLLL", "LLGLGG", "LLGGLG", "LLGGGL", "LGLLGG",
        "LGGLLG", "LGGGLL", "LGLGLG", "LGLGGL", "LGGLGL"
    )

    data class Bar(val xModul: Int, val wModul: Int)

    /** Jumlah total modul EAN-13 (incl guards): 3+42+5+42+3 = 95. */
    const val TOTAL_MODUL = 95

    /** Encode 13-digit -> daftar bar hitam [xModul, wModul] (1 modul = lebar bar narrow). */
    fun encodeBars(code13: String): List<Bar> {
        require(code13.length == 13 && code13.all { it.isDigit() }) {
            "EAN-13 butuh 13 digit, dapat: $code13"
        }
        val digits = code13.map { it.digitToInt() }
        val parity = PARITY[digits[0]]
        val bits = StringBuilder()
        bits.append("101")                                  // guard kiri
        for (i in 1..6) {                                   // 6 digit kiri (L/G)
            bits.append(if (parity[i - 1] == 'L') L[digits[i]] else G[digits[i]])
        }
        bits.append("01010")                                // guard tengah
        for (i in 7..12) { bits.append(R[digits[i]]) }      // 6 digit kanan (R)
        bits.append("101")                                  // guard kanan

        val bars = ArrayList<Bar>()
        var x = 0
        var i = 0
        while (i < bits.length) {
            val ch = bits[i]
            var run = 0
            while (i < bits.length && bits[i] == ch) { run++; i++ }
            if (ch == '1') bars.add(Bar(x, run))   // run-hitam = bar dgn lebar run modul
            x += run
        }
        return bars
    }

    /** Render bars -> bitmap 1-bit (hitam=1) selebar labelDot, memakai n dot per modul. */
    fun toBitmap(bars: List<Bar>, nDot: Int, widthDot: Int, heightDot: Int): ByteArray {
        val modulW = 95 * nDot                      // total modul * nDot
        val x0 = ((widthDot - modulW) / 2).coerceAtLeast(0)   // pusatkan
        val bytes = ByteArray(((widthDot + 7) / 8) * heightDot)
        for (b in bars) {
            val xa = x0 + b.xModul * nDot
            for (mi in 0 until b.wModul * nDot) {
                val cx = xa + mi
                if (cx < 0 || cx >= widthDot) continue
                for (y in 0 until heightDot) {
                    val idx = y * ((widthDot + 7) / 8) + (cx shr 3)
                    bytes[idx] = (bytes[idx].toInt() or (0x80 shr (cx and 7))).toByte()
                }
            }
        }
        return bytes
    }
}
