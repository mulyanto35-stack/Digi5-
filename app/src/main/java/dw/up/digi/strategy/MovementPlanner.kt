package dw.up.digi.strategy

import dw.up.digi.detection.Cell
import dw.up.digi.detection.CellScores
import java.util.PriorityQueue

enum class Direction(val dr: Int, val dc: Int) {
    RIGHT(0, 1), DOWN(1, 0), UP(-1, 0), LEFT(0, -1)
}

enum class ActionKind { MOVE, ATTACK, DASH }

data class Action(
    val kind: ActionKind,
    val target: Cell,
    val direction: Direction,
    val reason: String
)

object MovementPlanner {

    // ============================================================
    // KONSTANTA
    // ============================================================

    /**
     * Dash = maju 3 sel ke kanan + hancurkan SEMUA piramida di jalur +
     * ambil SEMUA item di jalur (termasuk drop dari piramida yang hancur).
     * Ini aksi paling efisien di game: 1 tap menggantikan 3 move + N attack.
     */
    private const val DASH_RANGE = 3

    /** Karakter selalu tampil di kolom 2 (kecuali baru geser kiri). */
    private const val PLAYER_COL = 2

    /** Kolom 5 = tepi kanan layar. Dunia bergulir; ini bukan tujuan akhir. */
    private const val RIGHT_EDGE_COL = 5

    /**
     * Dash dipakai jika ada >= 1 piramida di jalur 3 sel kanan.
     * Bahkan 1 piramida = untung besar (1 tap vs 1 attack + 1 move).
     */
    private const val DASH_PYRAMIDS_MIN = 1

    /**
     * Attack TIDAK memajukan posisi. Cost di pathfinding = 2
     * (1 tap attack + 1 move berikutnya untuk maju).
     * Detour 1 langkah (cost 1) selalu lebih murah dari attack.
     */
    private const val CLAW_ATTACK_COST = 2

    /** Simpan 1 claw sebagai reserve untuk dead-end darurat. */
    const val CLAW_RESERVE = 1

    /** How far the bot plans ahead to the right when no collectable is visible. */
    const val LOOKAHEAD = 3

    /** Outside a blockade, only Dash if more than this many charges are left. */
    private const val DASH_RESERVE = 1

    /**
     * Weight of the look-ahead per free cell. Must be large enough to outvote
     * the rightward bonus when the row is built up.
     */
    private const val LOOKAHEAD_WEIGHT = 20

    /**
     * Penalty for a cell that must first be smashed. Must clearly outweigh
     * the rightward bonus, otherwise the bot rams the pyramid although one
     * row further everything is free.
     */
    private const val BLOCKED_PENALTY = 90

    /** From this value on, a cell counts as a collectable. */
    private const val ITEM_SCORE = 0.06

    /** From this orange share on, the collectable is an energy orb and not a claw. */
    private const val ENERGY_ORANGE = 0.06

    /**
     * Energy orbs disappear on their own, claws stay lying around.
     * A detour of up to this many cells is therefore worth it to collect
     * the energy first.
     */
    private const val ENERGY_DETOUR = 3

    /**
     * Penalty for a cell from which the right column is no longer reachable.
     * Must outweigh the rightward bonus including the look-ahead.
     */
    private const val DEAD_END_PENALTY = 250

    /** Drop item dari piramida: bonus kecil, bukan alasan utama attack. */
    private const val PYRAMID_DROP_BONUS = 10

    // ============================================================
    // DATA CLASSES
    // ============================================================

    private data class Node(
        val cost: Int,
        val cell: Cell,
        val path: List<Action>
    ) : Comparable<Node> {
        override fun compareTo(o: Node) = cost.compareTo(o.cost)
    }

    private data class DashInfo(
        val pyramidCount: Int,
        val itemCount: Int,
        val landingFree: Boolean,
        val landingItem: Boolean,
        val totalValue: Int
    )

    // ============================================================
    // PUBLIC API: choose()
    // ============================================================

    /**
     * @param claws read claw supply, `null` if the number was not reliably read.
     *              Both are treated equally cautiously: smashing is then only
     *              allowed if nothing else works at all.
     */
    fun choose(
        player: Cell,
        cells: Map<Cell, CellScores>,
        history: List<Cell>,
        dashAvailable: Boolean = false,
        preview: Map<Cell, CellScores> = emptyMap(),
        forbiddenObstacles: Set<Cell> = emptySet(),
        dashCharges: Int = 0,
        stuck: Boolean = false,
        claws: Int? = null,
        settings: DwsNavigationSettings = DwsNavigationSettings(),
    ): Action? {

        val energyVisible = cells.any { (cell, score) ->
            cell != player && score.orange > ENERGY_ORANGE
        }

        // ---------------------------------------------------------
        // 1. DASH-SPAM MODE (opsional, profil tes)
        // ---------------------------------------------------------
        if (settings.dashSpamUntilZero && !energyVisible && dashAvailable && dashCharges > 0) {
            return Action(
                ActionKind.DASH,
                Cell(player.row, minOf(RIGHT_EDGE_COL, player.col + DASH_RANGE)),
                Direction.RIGHT,
                "Dash-Spam bis HUD 0"
            )
        }

        // ---------------------------------------------------------
        // 2. DASH PRIORITAS TERTINGGI
        //    Dash = maju 3 + hancurkan semua piramida di jalur +
        //    ambil semua item (termasuk drop). Bahkan 1 piramida
        //    di jalur sudah untung besar.
        // ---------------------------------------------------------
        if (dashAvailable && dashCharges > DASH_RESERVE) {
            val dash = dashAnalysis(player, cells)

            val shouldDash = when {
                // >= 2 piramida: SELALU dash (hancurkan semuanya)
                dash.pyramidCount >= 2 -> true

                // 1 piramida: dash (hancur 1, maju 3, mungkin drop item)
                dash.pyramidCount >= DASH_PYRAMIDS_MIN -> true

                // 0 piramida tapi ada item di jalur & tidak ada energy terlihat:
                // dash ambil item jauh dalam 1 tap
                dash.itemCount > 0 && !energyVisible -> true

                // 0 piramida tapi landing ada item
                dash.landingItem && !energyVisible -> true

                else -> false
            }

            if (shouldDash) {
                val target = Cell(player.row, minOf(RIGHT_EDGE_COL, player.col + DASH_RANGE))
                val reason = buildString {
                    append("Dash: ")
                    if (dash.pyramidCount > 0) append("${dash.pyramidCount} piramida")
                    if (dash.itemCount > 0) {
                        if (dash.pyramidCount > 0) append(" + ")
                        append("${dash.itemCount} item")
                    }
                    if (dash.pyramidCount == 0 && dash.itemCount == 0) append("maju 3")
                }
                return Action(ActionKind.DASH, target, Direction.RIGHT, reason)
            }
        }

        // ---------------------------------------------------------
        // 3. RUTE GRATIS PENUH (tanpa menghancurkan apa pun)
        // ---------------------------------------------------------
        route(player, cells, history, preview, forbiddenObstacles, false, settings)?.let { return it }

        // ---------------------------------------------------------
        // 4. RUTE DENGAN BOLEH MENGHANCURKAN (mayBreak = true)
        //    Hanya jika ada claw.
        // ---------------------------------------------------------
        if (claws == null || claws > 0) {
            route(player, cells, history, preview, forbiddenObstacles, true, settings)?.let { action ->
                return if (action.kind == ActionKind.ATTACK) {
                    action.copy(
                        reason = if (claws != null && claws <= CLAW_RESERVE) {
                            "Reserve: terpaksa hancurkan (tidak ada jalan gratis)"
                        } else {
                            "Buka jalur: hancurkan piramida"
                        }
                    )
                } else action
            }
        }

        // ---------------------------------------------------------
        // 5. DASH DARURAT (tanpa claw, stuck total)
        // ---------------------------------------------------------
        if (dashAvailable && dashCharges > 0) {
            return Action(
                ActionKind.DASH,
                Cell(player.row, minOf(RIGHT_EDGE_COL, player.col + DASH_RANGE)),
                Direction.RIGHT,
                "Notausgang ohne Kralle"
            )
        }

        return null
    }

    // ============================================================
    // ANALISA DASH
    // ============================================================

    /**
     * Analisa 3 sel ke kanan relatif terhadap karakter (kolom 2 -> 3, 4, 5).
     * SEMUA piramida & item di jalur akan terkena efek dash:
     *   - Piramida hancur
     *   - Item terambil
     *   - Drop dari piramida hancur langsung terambil
     */
    private fun dashAnalysis(player: Cell, cells: Map<Cell, CellScores>): DashInfo {
        val path = (1..DASH_RANGE)
            .map { Cell(player.row, player.col + it) }
            .filter { it.col <= RIGHT_EDGE_COL }

        if (path.isEmpty()) {
            return DashInfo(0, 0, true, false, 0)
        }

        val pyramids = path.count { cells[it]?.obstacle() == true }
        val items = path.count { (cells[it]?.item ?: 0.0) > ITEM_SCORE }
        val landing = path.last()
        val landingScore = cells[landing]
        val landingFree = landingScore?.obstacle() != true
        val landingItem = (landingScore?.item ?: 0.0) > ITEM_SCORE

        // Nilai dash: piramida = +30, item = +40, landing bebas = +10
        val value = pyramids * 30 + items * 40 + (if (landingFree) 10 else 0)

        return DashInfo(pyramids, items, landingFree, landingItem, value)
    }

    // ============================================================
    // ROUTE
    // ============================================================

    private fun allowedDirections(settings: DwsNavigationSettings): List<Direction> =
        if (settings.allowLeft) Direction.entries
        else Direction.entries.filterNot { it == Direction.LEFT }

    private fun route(
        player: Cell,
        cells: Map<Cell, CellScores>,
        history: List<Cell>,
        preview: Map<Cell, CellScores>,
        forbiddenObstacles: Set<Cell>,
        mayBreak: Boolean,
        settings: DwsNavigationSettings,
    ): Action? {

        val obstacles = cells.filterValues { it.obstacle() }.keys
        val blockedCells = if (mayBreak) forbiddenObstacles else forbiddenObstacles + obstacles

        // ---------------------------------------------------------
        // Profil tes forward-only
        // ---------------------------------------------------------
        if (settings.forceForwardAttack) {
            val energy = cells.filter { (cell, score) ->
                cell != player && score.orange > ENERGY_ORANGE
            }.keys

            if (settings.betterEnergyCollect) {
                val sameColumn = energy.filter { it.col == player.col }.toSet()
                forwardItemPath(player, sameColumn, cells, blockedCells, settings)
                    ?.firstOrNull()
                    ?.let { return it.copy(reason = "Energie in eigener Spalte zuerst") }
            }
            forwardItemPath(player, energy, cells, blockedCells, settings)
                ?.firstOrNull()
                ?.let { return it.copy(reason = "Energie direkt") }

            val right = Cell(player.row, player.col + 1)
            cells[right]?.let { score ->
                if (right !in forbiddenObstacles && (!score.obstacle() || mayBreak)) {
                    return Action(
                        if (score.obstacle()) ActionKind.ATTACK else ActionKind.MOVE,
                        right,
                        Direction.RIGHT,
                        if (score.obstacle()) "Vorwaertshindernis angreifen" else "Nur vorwaerts"
                    )
                }
            }
            return null
        }

        // ---------------------------------------------------------
        // Breakout phase: cari jalur terpendek ke tepi kanan
        // ---------------------------------------------------------
        if (mayBreak) {
            val rightEdge = cells.keys.filter { it.col == RIGHT_EDGE_COL }.toSet()
            shortestPath(player, rightEdge, cells, forbiddenObstacles, settings)
                ?.firstOrNull()
                ?.let { return it.copy(reason = "Ausbruchspfad") }
        }

        // ---------------------------------------------------------
        // Item path (energy diprioritaskan)
        // ---------------------------------------------------------
        itemPath(player, cells, blockedCells, settings)?.let { path ->
            val goal = path.last().target
            val energy = (cells[goal]?.orange ?: 0.0) > ENERGY_ORANGE
            return path.first().copy(reason = if (energy) "Energie zuerst" else "item route")
        }

        // Jika tak ada item & tak bisa capai kanan tanpa hancurkan -> return null
        // (biar choose() coba resource-backed escape)
        if (!mayBreak && !escapesRight(player, cells, blockedCells, false, settings)) return null

        // ---------------------------------------------------------
        // Heuristic neighbor scoring
        // ---------------------------------------------------------
        val oscillating = history.size >= 4 && history.takeLast(4).let {
            it[0] == it[2] && it[1] == it[3]
        }
        val previous = history.dropLast(1).lastOrNull()

        val candidates = allowedDirections(settings).mapNotNull { d ->
            val n = Cell(player.row + d.dr, player.col + d.dc)
            val scoreCell = cells[n] ?: return@mapNotNull null
            if (n in blockedCells) return@mapNotNull null

            val blocked = scoreCell.obstacle()
            var score = when (d) {
                Direction.RIGHT -> 100
                Direction.DOWN -> 20
                Direction.UP -> 15
                Direction.LEFT -> -40
            }

            if (!blocked) score += (scoreCell.highlight * 20).toInt()
            score += freeAhead(n, cells) * LOOKAHEAD_WEIGHT

            if (blocked) {
                // Attack TIDAK maju -> penalti besar KECUALI dead-end murni
                val canDetour = escapesRight(n, cells, blockedCells + player, mayBreak, settings)
                score -= if (canDetour) BLOCKED_PENALTY else BLOCKED_PENALTY / 3
                score += PYRAMID_DROP_BONUS
            }

            if (!blocked && !escapesRight(n, cells, blockedCells + player, mayBreak, settings)) {
                // Dead-end lokal: hanya penalti jika benar-benar buntu
                val genuinelyStuck = Direction.entries.none { dd ->
                    val nn = Cell(n.row + dd.dr, n.col + dd.dc)
                    cells[nn]?.obstacle() != true && cells[nn] != null
                }
                if (genuinelyStuck) score -= DEAD_END_PENALTY
            }

            if (n == previous) score -= 80
            if (oscillating && n == history[history.lastIndex - 1]) score -= 200

            // Preview kolom 5 (tepi layar)
            val look = preview[Cell(n.row, RIGHT_EDGE_COL)]
            if (look != null && look.pyramid > 0.17 && look.item <= 0.06) score -= 35
            if ((look?.item ?: 0.0) > 0.06) score += 45

            score to Action(
                if (blocked) ActionKind.ATTACK else ActionKind.MOVE,
                n, d,
                if (blocked) "Pyramide zerschlagen" else "Strecke rechts"
            )
        }

        // Filter anti-kantong: jika ada minimal 1 tetangga yang masih bisa
        // mencapai kanan, buang semua kantong buntu.
        val escaping = candidates.filter { (_, action) ->
            action.kind != ActionKind.MOVE ||
                escapesRight(action.target, cells, blockedCells + player, mayBreak, settings)
        }
        return (escaping.ifEmpty { candidates }).maxByOrNull { it.first }?.second
    }

    // ============================================================
    // LOOKAHEAD / FREE AHEAD
    // ============================================================

    /**
     * How many of the next [LOOKAHEAD] cells to the right of [from] are freely
     * walkable, normalised to [LOOKAHEAD]. Fewer cells are available at the
     * right edge - without normalisation the edge would otherwise be
     * systematically rated worse than the middle of the board.
     */
    private fun freeAhead(from: Cell, cells: Map<Cell, CellScores>): Int {
        val ahead = (1..LOOKAHEAD)
            .map { Cell(from.row, from.col + it) }
            .filter { it.col <= RIGHT_EDGE_COL }
        if (ahead.isEmpty()) return LOOKAHEAD
        return ahead.count { cells[it]?.obstacle() != true } * LOOKAHEAD / ahead.size
    }

    // ============================================================
    // ITEM PATH
    // ============================================================

    /**
     * Path to the nearest collectable - energy orbs first. They vanish after a
     * short time on their own, while claws stay lying around. Without this
     * preference the bot always took only the nearest symbol and walked past
     * an already visible energy orb until it was gone.
     */
    private fun itemPath(
        player: Cell,
        cells: Map<Cell, CellScores>,
        blocked: Set<Cell>,
        settings: DwsNavigationSettings,
    ): List<Action>? {
        val items = cells.filter { (cell, s) ->
            cell != player && s.item > ITEM_SCORE &&
                (!settings.collectOnlyEnergy || s.orange > ENERGY_ORANGE)
        }.keys
        if (items.isEmpty()) return null

        val any = forwardItemPath(player, items, cells, blocked, settings)
        val energy = items.filter { (cells[it]?.orange ?: 0.0) > ENERGY_ORANGE }.toSet()
        if (energy.isEmpty()) return any

        if (settings.betterEnergyCollect) {
            val sameColumn = energy.filter { it.col == player.col }.toSet()
            forwardItemPath(player, sameColumn, cells, blocked, settings)?.let { return it }
        }
        val toEnergy = forwardItemPath(player, energy, cells, blocked, settings) ?: return any
        if (any == null) return toEnergy
        return if (toEnergy.size <= any.size + ENERGY_DETOUR) toEnergy else any
    }

    /**
     * For items of the same kind the shortest single path does not simply win.
     * A target to the left of the player gets a clear backtracking penalty,
     * while targets further right are preferred.
     */
    private fun forwardItemPath(
        player: Cell,
        targets: Set<Cell>,
        cells: Map<Cell, CellScores>,
        blocked: Set<Cell>,
        settings: DwsNavigationSettings,
    ): List<Action>? =
        targets.mapNotNull { target ->
            shortestPath(player, setOf(target), cells, blocked, settings)?.let { path ->
                val backwards = (player.col - target.col).coerceAtLeast(0)
                Triple(path.size + backwards * 6, -target.col, path)
            }
        }.minWithOrNull(
            compareBy<Triple<Int, Int, List<Action>>> { it.first }.thenBy { it.second }
        )?.third

    // ============================================================
    // ESCAPE CHECK
    // ============================================================

    /**
     * Is the right column still reachable from [from] at all? Only this
     * distinguishes a pocket enclosed by pyramids from a normal detour.
     */
    private fun escapesRight(
        from: Cell,
        cells: Map<Cell, CellScores>,
        blocked: Set<Cell>,
        passObstacles: Boolean,
        settings: DwsNavigationSettings,
    ): Boolean {
        if (from.col >= RIGHT_EDGE_COL) return true
        val seen = mutableSetOf(from)
        val queue = ArrayDeque(listOf(from))
        while (queue.isNotEmpty()) {
            val c = queue.removeFirst()
            if (c.col >= RIGHT_EDGE_COL) return true
            for (d in allowedDirections(settings)) {
                val n = Cell(c.row + d.dr, c.col + d.dc)
                if (n in seen || n in blocked) continue
                val s = cells[n] ?: continue
                if (s.obstacle() && !passObstacles) continue
                seen += n
                queue += n
            }
        }
        return false
    }

    // ============================================================
    // SHORTEST PATH (Dijkstra)
    // ============================================================

    private fun shortestPath(
        start: Cell,
        targets: Set<Cell>,
        cells: Map<Cell, CellScores>,
        forbidden: Set<Cell>,
        settings: DwsNavigationSettings,
    ): List<Action>? {
        val q = PriorityQueue<Node>()
        q += Node(0, start, emptyList())
        val best = mutableMapOf(start to 0)

        while (q.isNotEmpty()) {
            val n = q.remove()
            if (n.cost != best[n.cell]) continue
            if (n.cell in targets && n.path.isNotEmpty()) return n.path

            for (d in allowedDirections(settings)) {
                val next = Cell(n.cell.row + d.dr, n.cell.col + d.dc)
                if (next in forbidden) continue
                val s = cells[next] ?: continue
                val obstacle = s.obstacle()
                val cost = n.cost + if (obstacle) CLAW_ATTACK_COST else 1
                if (cost < (best[next] ?: 999)) {
                    best[next] = cost
                    q += Node(
                        cost, next,
                        n.path + Action(
                            if (obstacle) ActionKind.ATTACK else ActionKind.MOVE,
                            next, d, "item route"
                        )
                    )
                }
            }
        }
        return null
    }

    // ============================================================
    // PLAN (multi-step batching)
    // ============================================================

    /**
     * Like [choose], but additionally returns the next steps of the same route
     * so they can be tapped one after another without re-analysing the image.
     *
     * Steps are only bundled if a misstep would have no consequences: if the
     * game swallows a tap, the whole chain shifts by one cell. Therefore the
     * corridor including one safety column must be free of pyramids -
     * otherwise a swallowed tap would consume a claw. When in doubt, it stays
     * a single step.
     */
    fun plan(
        player: Cell,
        cells: Map<Cell, CellScores>,
        history: List<Cell>,
        dashAvailable: Boolean = false,
        preview: Map<Cell, CellScores> = emptyMap(),
        forbiddenObstacles: Set<Cell> = emptySet(),
        dashCharges: Int = 0,
        stuck: Boolean = false,
        claws: Int? = null,
        maxSteps: Int = 1,
        settings: DwsNavigationSettings = DwsNavigationSettings(),
    ): List<Action> {
        val first = choose(
            player, cells, history, dashAvailable, preview, forbiddenObstacles,
            dashCharges, stuck, claws, settings
        ) ?: return emptyList()

        val single = listOf(first)
        if (maxSteps <= 1 || first.kind != ActionKind.MOVE) return single

        val mayBreak = claws != null && claws > CLAW_RESERVE
        val blocked = forbiddenObstacles +
            (if (mayBreak) emptySet() else cells.filterValues { it.obstacle() }.keys)

        val itemRoute = itemPath(player, cells, blocked, settings)
        val route = if (itemRoute != null) {
            if (itemRoute.first().target != first.target) return single
            itemRoute.takeWhile { it.kind == ActionKind.MOVE }.take(maxSteps)
        } else {
            if (first.direction != Direction.RIGHT) return single
            val out = mutableListOf(first)
            while (out.size < maxSteps) {
                val n = Cell(first.target.row, out.last().target.col + 1)
                if (n.col > RIGHT_EDGE_COL) break
                val s = cells[n] ?: break
                if (s.obstacle() || n in blocked) break
                out += Action(ActionKind.MOVE, n, Direction.RIGHT, "Strecke rechts")
            }
            out
        }

        if (route.size <= 1) return single
        return if (corridorClear(player, route, cells)) route else single
    }

    /** All cells of the route plus one column before and behind each must be free. */
    private fun corridorClear(
        player: Cell,
        route: List<Action>,
        cells: Map<Cell, CellScores>,
    ): Boolean {
        val rows = (route.map { it.target.row } + player.row).toSet()
        val cols = (route.map { it.target.col } + player.col)
        return rows.all { r ->
            (cols.min()..cols.max() + 1).all { c ->
                c > RIGHT_EDGE_COL || cells[Cell(r, c)]?.obstacle() != true
            }
        }
    }
}