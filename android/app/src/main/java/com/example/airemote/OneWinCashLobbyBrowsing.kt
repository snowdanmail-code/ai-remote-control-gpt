package com.example.airemote

/**
 * User-supplied 1win Cash Games screenshot: dropdown at top-left:
 *   Отсортировано по
 *   Ставки по возрастанию / Ставки по убыванию
 *   Игроки по возрастанию / Игроки по убыванию
 *
 * Read-only OCR. This does not assume other menus, stakes or game formats.
 */
enum class OneWinLobbySort(val russianLabel: String) {
    STAKES_ASC("Ставки по возрастанию"),
    STAKES_DESC("Ставки по убыванию"),
    PLAYERS_ASC("Игроки по возрастанию"),
    PLAYERS_DESC("Игроки по убыванию")
}

data class OneWinLobbySortMenu(
    val options: Map<OneWinLobbySort, NormalizedTextBox>,
    val recognizedHeader: Boolean
) {
    fun signature(): String = options.keys.sortedBy { it.ordinal }.joinToString(",")
    val navigable: Boolean get() = recognizedHeader &&
        options.size >= 3 && options.containsKey(OneWinLobbySort.STAKES_ASC)
}

object OneWinCashLobbySortParser {
    private val spaces = Regex("""\s+""")
    private val headings = setOf("отсортировано по", "sort by", "сортировка")

    fun read(lines: List<PositionedOcrLine>): OneWinLobbySortMenu? {
        val nearLeft = lines.filter { line ->
            line.box.valid() && line.box.centerX() in 50..485 &&
                line.box.centerY() in 145..460 &&
                line.text.length in 3..58
        }
        fun norm(raw: String) = raw.lowercase().trim().replace(spaces, " ")
        val heading = nearLeft.any { norm(it.text) in headings && it.box.centerY() < 240 }
        if (!heading) return null
        val options = mutableMapOf<OneWinLobbySort, NormalizedTextBox>()
        for (line in nearLeft) {
            val normalized = norm(line.text)
            val match = OneWinLobbySort.entries.singleOrNull {
                norm(it.russianLabel) == normalized
            } ?: continue
            if (options.containsKey(match)) return null // duplicate label is ambiguous
            options[match] = line.box
        }
        val ys = options.values.map { it.centerY() }.sorted()
        if (ys.zipWithNext().any { (a,b) -> b - a < 20 }) return null
        val menu = OneWinLobbySortMenu(options, true)
        return menu.takeIf { it.navigable }
    }
}

/**
 * Read-only page change detection. Row positions and moving average-pot
 * displays are NOT part of the identity; a repeated row set means no new
 * confirmed page. This prevents endless swiping at the bottom of the list.
 */
class OneWinCashLobbyBrowseTracker(private val maxSwipes: Int = 18) {
    private val seen = mutableSetOf<String>()
    private var previousPage: String? = null
    var swipesSent: Int = 0
        private set
    var repeatedPages: Int = 0
        private set

    fun reset() {
        seen.clear()
        previousPage = null
        swipesSent = 0
        repeatedPages = 0
    }

    fun pageSignature(tables: List<OneWinCashLobbyTable>): String =
        tables.joinToString("~") {
            listOf(it.label.trim().lowercase(), it.smallBlindCents,
                it.bigBlindCents, it.minBuyInCents, it.maxBuyInCents).joinToString("/")
        }

    fun recordConfirmedPage(tables: List<OneWinCashLobbyTable>): Boolean {
        if (tables.isEmpty()) return false
        val signature = pageSignature(tables)
        if (signature == previousPage) return false
        previousPage = signature
        val before = seen.size
        tables.forEach { t ->
            seen.add(listOf(t.label.trim().lowercase(), t.smallBlindCents,
                t.bigBlindCents).joinToString("|"))
        }
        repeatedPages = if (seen.size == before) repeatedPages + 1 else 0
        return true
    }

    fun canSwipe(): Boolean = swipesSent < maxSwipes && repeatedPages < 2
    fun markSwipeSubmitted(): Boolean {
        if (!canSwipe()) return false
        swipesSent++
        return true
    }
    fun distinctTablesSeen() = seen.size
}
