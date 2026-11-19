package dw.up.digi.detection

/** Screen rectangle of a HUD number, so the overlay can draw a box around it. */
data class HudBox(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Values read for the supplies. `null` means "not reliably recognised" and must be handled cautiously. */
data class HudCounters(
    val claws: Int? = null,
    val clawsBox: HudBox? = null,
    val dash: Int? = null,
    val dashBox: HudBox? = null,
    /** Normalised shapes of digits that matched no template - raw material for new templates. */
    val unknown: List<String> = emptyList(),
)

/**
 * Reads the supply counts for attack claws and Dash below the playfield.
 *
 * The digits are outline glyphs: only the contour is white, the fill sits colour-wise beneath it.
 * The game always renders them pixel-identically at the same position, so the contour is not
 * interpreted but normalised to a fixed grid and compared directly against recorded templates.
 * A shape classifier would be considerably less reliable with this very bold, slightly italic
 * typeface - normalised to 9x6, a one and a two almost collapse into the same blocks.
 *
 * All windows are given relative to the calibrated grid. Measured on the device
 * (1080x2400, grid 27/847/1021/1664): claw count at y=2142..2170, Dash count at y=2227..2256,
 * both starting at x=290; to the left of that are only the symbol and the regrowth timer.
 */
object HudCounterReader {
    private const val CLAWS_CENTER = .602
    private const val DASH_CENTER = .706
    private const val BAND_HALF = .032
    private const val X_FROM = .26
    internal const val X_TO = .42
    private const val WHITE = 225

    /** At most this many empty columns still count as part of the same digit. */
    private const val COLUMN_GAP = 1

    /** A digit must be at least this tall, otherwise it is image noise. */
    private const val MIN_HEIGHT = 12

    /** From this much contour on, a grid cell of the normalised shape counts as set. */
    private const val CELL_ON = 35

    internal const val ROWS = 16
    internal const val COLS = 12

    /** Largest still-accepted distance to the best template (out of [ROWS]*[COLS] cells). */
    private const val MAX_DISTANCE = 26

    /** By this much the best template must beat the second best. */
    private const val MIN_MARGIN = 8

    /**
     * Recorded contour shapes. Only digits actually observed on the device are populated.
     * Anything else deliberately yields `null` instead of a guess - movement planning
     * treats "unknown" as cautiously as "almost empty" anyway.
     */
    private const val FIVE =
        "001111111110" +   // ..##########
        "011111111111" +   // .###########
        "010000000001" +   // .#.........#
        "010000000001" +   // .#.........#
        "010000111111" +   // .#....######
        "010001111100" +   // .#...#####..
        "010000111110" +   // .#....#####.
        "010000000010" +   // .#........#.
        "010000000001" +   // .#.........#
        "011111100001" +   // .######....#
        "011100110001" +   // .###..##...#
        "010111110001" +   // .#.#####...#
        "110000000001" +   // ##.........#
        "110000000011" +   // ##........##
        "011000001110" +   // .##.....###.
        "001111111000"     // ..#######...

    private val TEMPLATES: Map<Int, String> = mapOf(
        1 to ONE,
        2 to TWO,
        3 to THREE,
        5 to FIVE,
    )

    fun read(width: Int, height: Int, argb: IntArray, bounds: GridBounds): HudCounters {
        val claws = readBand(width, height, argb, bounds, CLAWS_CENTER)
        val dash = readBand(width, height, argb, bounds, DASH_CENTER)
        val unknown = mutableListOf<String>()
        if (claws?.first == null) unknown += glyphs(width, height, argb, bounds, CLAWS_CENTER).map { it.bits }
        if (dash?.first == null) unknown += glyphs(width, height, argb, bounds, DASH_CENTER).map { it.bits }
        return HudCounters(claws?.first, claws?.second, dash?.first, dash?.second, unknown)
    }

    /**
     * Reads the number of one band. The box is returned even when the digit stays unreadable -
     * it is visible after all, and the overlay should show that honestly.
     */
    internal fun readBand(
        width: Int,
        height: Int,
        argb: IntArray,
        bounds: GridBounds,
        center: Double,
    ): Pair<Int?, HudBox>? {
        val glyphs = glyphs(width, height, argb, bounds, center)
        if (glyphs.isEmpty()) return null
        var value: Int? = 0
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE
        for (glyph in glyphs) {
            left = minOf(left, glyph.box.left); right = maxOf(right, glyph.box.right)
            top = minOf(top, glyph.box.top); bottom = maxOf(bottom, glyph.box.bottom)
            val digit = classify(glyph.bits)
            value = if (digit == null || value == null) null else value * 10 + digit
        }
        return value to HudBox(left, top, right, bottom)
    }

    internal data class Glyph(val bits: String, val box: HudBox)

    internal fun glyphs(
        width: Int,
        height: Int,
        argb: IntArray,
        bounds: GridBounds,
        center: Double,
    ): List<Glyph> {
        val gridHeight = (bounds.bottom - bounds.top).toDouble()
        val gridWidth = (bounds.right - bounds.left).toDouble()
        val y0 = (bounds.bottom + (center - BAND_HALF) * gridHeight).toInt().coerceIn(0, height - 1)
        val y1 = (bounds.bottom + (center + BAND_HALF) * gridHeight).toInt().coerceIn(y0 + 1, height)
        val x0 = (bounds.left + X_FROM * gridWidth).toInt().coerceIn(0, width - 1)
        val x1 = (bounds.left + X_TO * gridWidth).toInt().coerceIn(x0 + 1, width)
        val w = x1 - x0
        val h = y1 - y0

        val ink = BooleanArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = argb[(y0 + y) * width + (x0 + x)]
                if ((p shr 16 and 255) > WHITE && (p shr 8 and 255) > WHITE && (p and 255) > WHITE) {
                    ink[y * w + x] = true
                }
            }
        }

        return columnGroups(ink, w, h).mapNotNull { (cx0, cx1) ->
            var ry0 = Int.MAX_VALUE
            var ry1 = Int.MIN_VALUE
            for (y in 0 until h) for (x in cx0..cx1) if (ink[y * w + x]) {
                if (y < ry0) ry0 = y
                if (y > ry1) ry1 = y
            }
            if (ry1 - ry0 + 1 < MIN_HEIGHT) return@mapNotNull null
            Glyph(
                normalize(ink, w, cx0, cx1, ry0, ry1),
                HudBox(x0 + cx0, y0 + ry0, x0 + cx1 + 1, y0 + ry1 + 1),
            )
        }
    }

    private fun columnGroups(ink: BooleanArray, w: Int, h: Int): List<Pair<Int, Int>> {
        val filled = BooleanArray(w)
        for (x in 0 until w) for (y in 0 until h) if (ink[y * w + x]) { filled[x] = true; break }
        val groups = mutableListOf<Pair<Int, Int>>()
        var start = -1
        var gap = 0
        for (x in 0 until w) {
            if (filled[x]) {
                if (start < 0) start = x
                gap = 0
            } else if (start >= 0) {
                gap++
                if (gap > COLUMN_GAP) { groups += start to x - gap; start = -1; gap = 0 }
            }
        }
        if (start >= 0) groups += start to w - 1 - gap
        return groups
    }

    /** Rasterises the glyph contour to [ROWS]x[COLS], independent of its pixel size. */
    private fun normalize(ink: BooleanArray, w: Int, cx0: Int, cx1: Int, ry0: Int, ry1: Int): String {
        val sw = cx1 - cx0 + 1
        val sh = ry1 - ry0 + 1
        val bits = StringBuilder(ROWS * COLS)
        for (gr in 0 until ROWS) {
            val ya = gr * sh / ROWS
            val yb = maxOf(ya + 1, (gr + 1) * sh / ROWS)
            for (gc in 0 until COLS) {
                val xa = gc * sw / COLS
                val xb = maxOf(xa + 1, (gc + 1) * sw / COLS)
                var n = 0
                var on = 0
                for (y in ya until yb) for (x in xa until xb) {
                    n++
                    if (ink[(ry0 + y) * w + (cx0 + x)]) on++
                }
                bits.append(if (n > 0 && on * 100 / n >= CELL_ON) '1' else '0')
            }
        }
        return bits.toString()
    }

    private fun classify(bits: String): Int? {
        var best = Int.MAX_VALUE
        var second = Int.MAX_VALUE
        var digit: Int? = null
        for ((value, template) in TEMPLATES) {
            var distance = 0
            for (i in bits.indices) if (bits[i] != template[i]) distance++
            if (distance < best) { second = best; best = distance; digit = value }
            else if (distance < second) second = distance
        }
        if (best > MAX_DISTANCE) return null
        if (TEMPLATES.size > 1 && second - best < MIN_MARGIN) return null
        return digit
    }

    internal const val CLAWS_BAND = CLAWS_CENTER
    internal const val DASH_BAND = DASH_CENTER
    internal const val PAWS_BAND = .487

    private const val ONE =
        "000001111000" +   // .....####...
        "000011001100" +   // ....##..##..
        "001110001100" +   // ..###...##..
        "011000001100" +   // .##.....##..
        "110000001100" +   // ##......##..
        "010000001100" +   // .#......##..
        "011010001100" +   // .##.#...##..
        "001110001100" +   // ..###...##..
        "000110001100" +   // ...##...##..
        "000110001100" +   // ...##...##..
        "000110001100" +   // ...##...##..
        "001110001110" +   // ..###...###.
        "010000000001" +   // .#.........#
        "010000000001" +   // .#.........#
        "010000000001" +   // .#.........#
        "001111111110"     // ..#########.

    private const val TWO =
        "000001110000" +   // .....###....
        "001111111100" +   // ..########..
        "011000000010" +   // .##.......#.
        "110000000011" +   // ##........##
        "110001100001" +   // ##...##....#
        "010011110001" +   // .#..####...#
        "001100100001" +   // ..##..#....#
        "000001100011" +   // .....##...##
        "000011000010" +   // ....##....#.
        "000110000110" +   // ...##....##.
        "001100001100" +   // ..##....##..
        "010000011111" +   // .#.....#####
        "110000000001" +   // ##.........#
        "110000000001" +   // ##.........#
        "110000000001" +   // ##.........#
        "011111111110"     // .##########.

    private const val THREE =
        "000111110000" +   // ...####.....
        "001111111100" +   // ..########..
        "010000000010" +   // .#........#.
        "110000000001" +   // ##.........#
        "010011100001" +   // .#..###....#
        "011111100001" +   // .#####.....#
        "001111000011" +   // ..####....##
        "001000000010" +   // ..#.......#.
        "001000000011" +   // ..#........#
        "001111000001" +   // ..####.....#
        "011111110001" +   // .#######...#
        "110111100001" +   // ##.####....#
        "110000000001" +   // ##.........#
        "110000000011" +   // ##........##
        "111000001110" +   // ###.....###.
        "001111111000"     // ..#######...
}
