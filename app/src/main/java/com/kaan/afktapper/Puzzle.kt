package com.kaan.afktapper

import org.json.JSONArray
import org.json.JSONObject

// Bu dosyada Android sınıfı yok: telefon olmadan JUnit ile test edilebilir.

/** Tepsideki bir parça. shape[r][c] = dolu mu (kenarları sıkı kırpılmış). cx,cy: ekranda 0-1000 ölçeğinde merkez. */
class PuzzlePiece(val cx: Float, val cy: Float, val shape: Array<BooleanArray>) {
    val rows: Int get() = shape.size
    val cols: Int get() = shape[0].size
}

sealed class PuzzleRead {
    /** Oyun tahtası ekranı. left/top/right/bottom: hücre ızgarasının dış kenarları (0-1000). */
    class Game(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val cells: Array<BooleanArray>,
        val pieces: List<PuzzlePiece>
    ) : PuzzleRead()

    /** Tahta değil (reklam, menü, oyun bitti...). Gemini'nin önerdiği genel eylemler. */
    class Other(val actions: List<AiAction>) : PuzzleRead()
}

object JsonText {
    /** Kod çitlerini ve JSON dışı yazıyı ayıklar. */
    fun clean(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```")) {
            s = s.removePrefix("```json").removePrefix("```JSON").removePrefix("```")
                .removeSuffix("```").trim()
        }
        val a = s.indexOfFirst { it == '{' || it == '[' }
        val b = s.indexOfLast { it == '}' || it == ']' }
        return if (a in 0 until b) s.substring(a, b + 1) else s
    }

    fun actions(arr: JSONArray, max: Int): List<AiAction> {
        val list = ArrayList<AiAction>()
        val n = minOf(arr.length(), max.coerceAtLeast(1))
        for (i in 0 until n) {
            val o = arr.optJSONObject(i) ?: continue
            list.add(
                AiAction(
                    type = o.optString("action", "wait").trim().lowercase(),
                    x = o.optInt("x", 500),
                    y = o.optInt("y", 500),
                    x2 = o.optInt("x2", -1),
                    y2 = o.optInt("y2", -1),
                    reason = o.optString("reason", "").take(60)
                )
            )
        }
        return list
    }
}

object PuzzleParser {

    private fun isFilled(c: Char) = c == '#' || c == '1' || c == 'X' || c == 'x' || c == '█' || c == '■'

    fun parse(raw: String): PuzzleRead {
        val obj = JSONObject(JsonText.clean(raw))
        val screen = obj.optString("screen", "game").trim().lowercase()
        if (screen != "game") {
            val list = JsonText.actions(obj.optJSONArray("actions") ?: JSONArray(), 2)
            return PuzzleRead.Other(
                if (list.isEmpty()) listOf(AiAction("wait", 500, 500, -1, -1, "")) else list
            )
        }

        val board = obj.optJSONObject("board") ?: throw GeminiException(0, "Tahta okunamadı")
        val rowsJson = board.optJSONArray("cells") ?: throw GeminiException(0, "Tahta hücreleri okunamadı")
        val rowCount = rowsJson.length()
        if (rowCount < 3 || rowCount > 12) throw GeminiException(0, "Tahta boyutu geçersiz ($rowCount satır)")

        val rowStrings = (0 until rowCount).map { rowsJson.optString(it, "") }
        val colCount = rowStrings[0].length
        if (colCount < 3 || colCount > 12 || rowStrings.any { it.length != colCount }) {
            throw GeminiException(0, "Tahta satırları tutarsız okundu")
        }
        val cells = Array(rowCount) { r -> BooleanArray(colCount) { c -> isFilled(rowStrings[r][c]) } }

        val left = board.optDouble("left", Double.NaN).toFloat()
        val top = board.optDouble("top", Double.NaN).toFloat()
        val right = board.optDouble("right", Double.NaN).toFloat()
        val bottom = board.optDouble("bottom", Double.NaN).toFloat()
        val rectOk = !left.isNaN() && !top.isNaN() && !right.isNaN() && !bottom.isNaN() &&
            left >= 0f && top >= 0f && right <= 1000f && bottom <= 1000f &&
            right - left > 100f && bottom - top > 100f
        if (!rectOk) throw GeminiException(0, "Tahta konumu okunamadı")

        val pieces = ArrayList<PuzzlePiece>()
        val piecesJson = obj.optJSONArray("pieces") ?: JSONArray()
        for (i in 0 until minOf(piecesJson.length(), 4)) {
            val p = piecesJson.optJSONObject(i) ?: continue
            val cx = p.optDouble("cx", Double.NaN).toFloat()
            val cy = p.optDouble("cy", Double.NaN).toFloat()
            if (cx.isNaN() || cy.isNaN()) continue
            val shapeJson = p.optJSONArray("shape") ?: continue
            val shape = normalize((0 until shapeJson.length()).map { shapeJson.optString(it, "") }) ?: continue
            pieces.add(PuzzlePiece(cx.coerceIn(0f, 1000f), cy.coerceIn(0f, 1000f), shape))
        }
        return PuzzleRead.Game(left, top, right, bottom, cells, pieces)
    }

    /** Boş kenar satır/sütunlarını atar; dolu hücre yoksa null. */
    fun normalize(rows: List<String>): Array<BooleanArray>? {
        if (rows.isEmpty()) return null
        val width = rows.maxOf { it.length }
        if (width == 0) return null
        val grid = Array(rows.size) { r -> BooleanArray(width) { c -> c < rows[r].length && isFilled(rows[r][c]) } }
        val usedRows = grid.indices.filter { r -> grid[r].any { it } }
        val usedCols = (0 until width).filter { c -> grid.any { it[c] } }
        if (usedRows.isEmpty()) return null
        val r0 = usedRows.first()
        val r1 = usedRows.last()
        val c0 = usedCols.first()
        val c1 = usedCols.last()
        return Array(r1 - r0 + 1) { r -> BooleanArray(c1 - c0 + 1) { c -> grid[r0 + r][c0 + c] } }
    }
}

object PuzzleGeometry {
    /** Parçanın merkezinin ekranda (0-1000) olması gereken yatay konum. */
    fun targetX(left: Float, right: Float, cols: Int, col: Int, pieceCols: Int): Float =
        left + (right - left) / cols * (col + pieceCols / 2f)

    fun targetY(top: Float, bottom: Float, rows: Int, row: Int, pieceRows: Int): Float =
        top + (bottom - top) / rows * (row + pieceRows / 2f)
}

/** Block Blast tarzı: parçaları tahtaya yerleştirip satır/sütun temizleyen çözücü. */
object BlockSolver {

    class Placement(val pieceIndex: Int, val row: Int, val col: Int)

    class Plan(val placements: List<Placement>, val lines: Int)

    private class State(
        val board: Array<BooleanArray>,
        val moves: List<Placement>,
        val lines: Int,
        val lineScore: Int,
        val score: Double
    )

    private const val BEAM = 40

    fun canPlace(board: Array<BooleanArray>, shape: Array<BooleanArray>, r: Int, c: Int): Boolean {
        if (r < 0 || c < 0 || r + shape.size > board.size || c + shape[0].size > board[0].size) return false
        for (i in shape.indices) {
            for (j in shape[i].indices) {
                if (shape[i][j] && board[r + i][c + j]) return false
            }
        }
        return true
    }

    /** Parçayı koyar, dolan satır ve sütunları aynı anda temizler. (yeni tahta, temizlenen çizgi sayısı) */
    fun place(board: Array<BooleanArray>, shape: Array<BooleanArray>, r: Int, c: Int): Pair<Array<BooleanArray>, Int> {
        val b = Array(board.size) { board[it].clone() }
        for (i in shape.indices) {
            for (j in shape[i].indices) {
                if (shape[i][j]) b[r + i][c + j] = true
            }
        }
        val rows = b.size
        val cols = b[0].size
        val fullRows = (0 until rows).filter { i -> b[i].all { it } }
        val fullCols = (0 until cols).filter { j -> (0 until rows).all { i -> b[i][j] } }
        for (i in fullRows) for (j in 0 until cols) b[i][j] = false
        for (j in fullCols) for (i in 0 until rows) b[i][j] = false
        return b to (fullRows.size + fullCols.size)
    }

    /** Yüksek = iyi tahta: çok boş yer, az "kuyu" (tek başına boş hücre), düzgün kenarlar. */
    private fun evaluate(b: Array<BooleanArray>): Double {
        val rows = b.size
        val cols = b[0].size
        var empty = 0
        var isolated = 0
        var transitions = 0
        for (i in 0 until rows) {
            for (j in 0 until cols) {
                if (!b[i][j]) {
                    empty++
                    val up = i == 0 || b[i - 1][j]
                    val down = i == rows - 1 || b[i + 1][j]
                    val left = j == 0 || b[i][j - 1]
                    val right = j == cols - 1 || b[i][j + 1]
                    if (up && down && left && right) isolated++
                }
                if (j + 1 < cols && b[i][j] != b[i][j + 1]) transitions++
                if (i + 1 < rows && b[i][j] != b[i + 1][j]) transitions++
            }
        }
        return empty * 2.0 - isolated * 60.0 - transitions * 4.0
    }

    private fun score(placed: Int, lineScore: Int, board: Array<BooleanArray>): Double =
        placed * 100000.0 + lineScore * 400.0 + evaluate(board)

    private fun signature(shape: Array<BooleanArray>): String =
        shape.joinToString("/") { row -> row.joinToString("") { if (it) "#" else "." } }

    private fun permutations(items: List<Int>): List<List<Int>> {
        if (items.size <= 1) return listOf(items)
        val out = ArrayList<List<Int>>()
        for (i in items.indices) {
            val rest = items.filterIndexed { idx, _ -> idx != i }
            for (p in permutations(rest)) out.add(listOf(items[i]) + p)
        }
        return out
    }

    /**
     * Tüm sıralamaları dener, her adımda en iyi [BEAM] durumu tutar.
     * Önce en çok parçayı yerleştirmeyi, sonra en çok çizgi temizlemeyi, sonra tahtanın düzenini önceler.
     */
    fun plan(board: Array<BooleanArray>, pieces: List<Array<BooleanArray>>): Plan {
        val n = minOf(pieces.size, 4)
        if (n == 0) return Plan(emptyList(), 0)

        val seen = HashSet<String>()
        var best: State? = null

        for (order in permutations((0 until n).toList())) {
            if (!seen.add(order.joinToString("|") { signature(pieces[it]) })) continue

            var beam = listOf(State(board, emptyList(), 0, 0, score(0, 0, board)))
            val finished = ArrayList<State>()

            for (pi in order) {
                val shape = pieces[pi]
                val next = ArrayList<State>()
                for (s in beam) {
                    var any = false
                    for (r in 0..(board.size - shape.size)) {
                        for (c in 0..(board[0].size - shape[0].size)) {
                            if (!canPlace(s.board, shape, r, c)) continue
                            any = true
                            val (nb, k) = place(s.board, shape, r, c)
                            val ls = s.lineScore + k * k
                            val moves = s.moves + Placement(pi, r, c)
                            next.add(State(nb, moves, s.lines + k, ls, score(moves.size, ls, nb)))
                        }
                    }
                    if (!any) finished.add(s)
                }
                beam = next.sortedByDescending { it.score }.take(BEAM)
                if (beam.isEmpty()) break
            }
            finished.addAll(beam)

            for (s in finished) {
                val cur = best
                if (cur == null || s.score > cur.score) best = s
            }
        }

        val result = best ?: return Plan(emptyList(), 0)
        return Plan(result.moves, result.lines)
    }
}
