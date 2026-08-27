package com.zpos.label.bc

/**
 * Code 128 render — mirror logika z1pos `src/lib/barcode-code39.ts` (v3).
 * Internal format v3 = "2" + 4 digit id (0-pad) + 1 EAN check digit => 6 digit
 * numerik , dicetak Code 128-C (2 digit/simbol). Output: daftar bar hitam
 * [posisiModul, lebarModul] + totalModul (tanpa quiet).
 */
object Code128 {

    private val C128: Map<Int, String> = mapOf(
        0 to "212222",1 to "222122",2 to "222221",3 to "121223",4 to "121322",
        5 to "131222",6 to "122213",7 to "122312",8 to "132212",9 to "221213",
        10 to "221312",11 to "231212",12 to "112232",13 to "122132",14 to "122231",
        15 to "113222",16 to "123122",17 to "123221",18 to "223211",19 to "221132",
        20 to "221231",21 to "213212",22 to "223112",23 to "312131",24 to "311222",
        25 to "321122",26 to "321221",27 to "312212",28 to "322112",29 to "322211",
        30 to "212123",31 to "212321",32 to "232121",33 to "111323",34 to "131123",
        35 to "131321",36 to "112313",37 to "132113",38 to "132311",39 to "211313",
        40 to "231113",41 to "231311",42 to "112133",43 to "112331",44 to "132131",
        45 to "113123",46 to "113321",47 to "133121",48 to "313121",49 to "211331",
        50 to "231131",51 to "213113",52 to "213311",53 to "213131",54 to "311123",
        55 to "311321",56 to "331121",57 to "312113",58 to "312311",59 to "332111",
        60 to "314111",61 to "221411",62 to "431111",63 to "111224",64 to "111422",
        65 to "121124",66 to "121421",67 to "141122",68 to "141221",69 to "112214",
        70 to "112412",71 to "122114",72 to "122411",73 to "142112",74 to "142211",
        75 to "241211",76 to "221114",77 to "413111",78 to "241112",79 to "134111",
        80 to "111242",81 to "121142",82 to "121241",83 to "114212",84 to "124112",
        85 to "124211",86 to "411212",87 to "421112",88 to "421211",89 to "212141",
        90 to "214121",91 to "412121",92 to "111143",93 to "111341",94 to "131141",
        95 to "114113",96 to "114311",97 to "411113",98 to "411311",99 to "113141",
        100 to "114131",101 to "311141",102 to "411131",103 to "211412",
        104 to "211214",105 to "211232",106 to "2331112"
    )

    const val QUIET = 10

    data class Bar(val xModul: Int, val wModul: Int)
    data class Encoded(val bars: List<Bar>, val totalModul: Int)

    /** Generate barcode internal v3 utk id produk (6 digit). */
    fun generateV3(id: Long): String {
        val base = "2" + (kotlin.math.abs(id)).toString().padStart(4, '0').take(4)
        return base + eanCheckDigit(base)
    }

    /** Check digit EAN utk panjang data bebas (bobot 1-3 bergantian dr kanan). */
    fun eanCheckDigit(digits: String): Int {
        val n = digits.length
        var sum = 0
        for (i in 0 until n) {
            val d = digits[i].digitToIntOrNull() ?: 0
            val w = if ((n - 1 - i) % 2 == 0) 3 else 1
            sum += d * w
        }
        return (10 - (sum % 10)) % 10
    }

    /** Encode 6-digit numerik (v3) -> Code 128-C. */
    fun encodeCDigits(digits: String): Encoded? {
        if (digits.isEmpty() || digits.length % 2 != 0 || digits.any { !it.isDigit() }) return null
        val vals = ArrayList<Int>()
        var i = 0
        while (i < digits.length) {
            vals.add(digits.substring(i, i + 2).toInt()); i += 2
        }
        return encodeFromValues(vals, 105) // Start Code C
    }

    /** Encode Code 128-B: tiap char -> (ascii-32). */
    fun encodeBText(text: String): Encoded {
        val vals = text.map { c -> ((c.code - 32).coerceIn(0, 95)) }
        return encodeFromValues(vals, 104)
    }

    private fun encodeFromValues(vals: List<Int>, start: Int): Encoded {
        var sum = start
        for (idx in vals.indices) sum += vals[idx] * (idx + 1)
        val all = listOf(start) + vals + (sum % 103) + 106
        val bits = StringBuilder()
        var totalModul = 0
        for (v in all) {
            val p = C128[v] ?: "212222"
            bits.append(p)
            for (c in p) totalModul += c.digitToInt()
        }
        val bars = ArrayList<Bar>()
        var x = 0
        var drawing = true
        for (ch in bits) {
            val w = ch.digitToInt()
            if (drawing) bars.add(Bar(x, w))
            x += w
            drawing = !drawing
        }
        return Encoded(bars, totalModul)
    }
}
