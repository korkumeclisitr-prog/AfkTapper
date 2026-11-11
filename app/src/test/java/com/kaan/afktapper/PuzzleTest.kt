package com.kaan.afktapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PuzzleTest {

    private fun grid(vararg rows: String): Array<BooleanArray> =
        Array(rows.size) { r -> BooleanArray(rows[r].length) { c -> rows[r][c] == '#' } }

    private val emptyRows = List(8) { "........" }

    @Test
    fun completesAlmostFullRow() {
        val board = grid(*(emptyRows.take(7) + "...#####").toTypedArray())
        val plan = BlockSolver.plan(board, listOf(grid("###")))
        assertEquals(1, plan.placements.size)
        assertEquals(7, plan.placements[0].row)
        assertEquals(0, plan.placements[0].col)
        assertEquals(1, plan.lines)
    }

    @Test
    fun completesAlmostFullColumn() {
        val rows = List(8) { i -> if (i < 5) "#......." else "........" }
        val board = grid(*rows.toTypedArray())
        // 3 hücrelik dikey parça sütunu tamamlamalı.
        val plan = BlockSolver.plan(board, listOf(grid("#", "#", "#")))
        assertEquals(1, plan.placements.size)
        assertEquals(5, plan.placements[0].row)
        assertEquals(0, plan.placements[0].col)
        assertEquals(1, plan.lines)
    }

    @Test
    fun nothingFitsGivesEmptyPlan() {
        val rows = List(8) { i -> if (i == 0) ".#######" else "########" }
        val plan = BlockSolver.plan(grid(*rows.toTypedArray()), listOf(grid("##", "##")))
        assertTrue(plan.placements.isEmpty())
    }

    @Test
    fun placesAllThreePiecesWithoutOverlap() {
        val board = grid(*emptyRows.toTypedArray())
        val pieces = listOf(grid("##", "##"), grid("#", "#", "#"), grid("###"))
        val plan = BlockSolver.plan(board, pieces)
        assertEquals(3, plan.placements.size)
        assertEquals(setOf(0, 1, 2), plan.placements.map { it.pieceIndex }.toSet())

        var b = board
        for (pl in plan.placements) {
            val shape = pieces[pl.pieceIndex]
            assertTrue(BlockSolver.canPlace(b, shape, pl.row, pl.col))
            b = BlockSolver.place(b, shape, pl.row, pl.col).first
        }
    }

    @Test
    fun placesAsManyPiecesAsPossible() {
        // Sadece 1 hücre boş: 1x1 parça sığar, 2x2 sığmaz.
        val rows = List(8) { i -> if (i == 3) "####.###" else "########" }
        val plan = BlockSolver.plan(grid(*rows.toTypedArray()), listOf(grid("##", "##"), grid("#")))
        assertEquals(1, plan.placements.size)
        assertEquals(1, plan.placements[0].pieceIndex)
        assertEquals(3, plan.placements[0].row)
        assertEquals(4, plan.placements[0].col)
    }

    @Test
    fun clearsRowAndColumnTogether() {
        // (0,0) boş; tek hücre koyunca hem 0. satır hem 0. sütun dolar.
        val (b, lines) = BlockSolver.place(
            grid(
                ".###",
                "#...",
                "#...",
                "#..."
            ),
            grid("#"),
            0, 0
        )
        assertEquals(2, lines)
        assertTrue(b.all { row -> row.none { it } })
    }

    @Test
    fun geometryMapsCellsToScreen() {
        // Tahta 100..900 arası, 8 sütun => hücre 100 birim.
        assertEquals(150f, PuzzleGeometry.targetX(100f, 900f, 8, 0, 1), 0.001f)
        assertEquals(300f, PuzzleGeometry.targetX(100f, 900f, 8, 1, 3), 0.001f)
        // Tahta 200..1000 arası, 8 satır => hücre 100 birim; 3 satırlık parça 0. satırda: 200 + 1.5*100.
        assertEquals(350f, PuzzleGeometry.targetY(200f, 1000f, 8, 0, 3), 0.001f)
    }

    private fun gameJson(cellsRows: List<String>, extra: String = ""): String {
        val cells = cellsRows.joinToString(",") { "\"$it\"" }
        return """
            {"screen":"game",
             "board":{"left":100,"top":200,"right":900,"bottom":1000,"cells":[$cells]},
             "pieces":[
               {"cx":200,"cy":900,"shape":["....",".##.",".#.."]},
               {"cx":500,"cy":900,"shape":["###"]}
             ]$extra}
        """.trimIndent()
    }

    @Test
    fun parsesGameAndTrimsPieces() {
        val rows = List(8) { i -> if (i == 7) "#####..." else "........" }
        val read = PuzzleParser.parse("```json\n" + gameJson(rows) + "\n```")
        assertTrue(read is PuzzleRead.Game)
        read as PuzzleRead.Game
        assertEquals(8, read.cells.size)
        assertEquals(8, read.cells[0].size)
        assertTrue(read.cells[7][0])
        assertTrue(read.cells[7][4])
        assertTrue(!read.cells[7][5])
        assertEquals(2, read.pieces.size)
        // "....", ".##.", ".#.." => kırpılmış 2x2: ## / #.
        val shape = read.pieces[0].shape
        assertEquals(2, shape.size)
        assertEquals(2, shape[0].size)
        assertTrue(shape[0][0] && shape[0][1] && shape[1][0] && !shape[1][1])
        assertEquals(1, read.pieces[1].rows)
        assertEquals(3, read.pieces[1].cols)
    }

    @Test
    fun parsesOtherScreen() {
        val read = PuzzleParser.parse("""{"screen":"other","actions":[{"action":"tap","x":900,"y":80,"reason":"reklamı kapat"}]}""")
        assertTrue(read is PuzzleRead.Other)
        read as PuzzleRead.Other
        assertEquals(1, read.actions.size)
        assertEquals("tap", read.actions[0].type)
        assertEquals(900, read.actions[0].x)
    }

    @Test
    fun rejectsRaggedBoard() {
        val rows = List(8) { i -> if (i == 3) "......." else "........" }
        try {
            PuzzleParser.parse(gameJson(rows))
            fail("Tutarsız satır uzunluğu reddedilmeliydi")
        } catch (e: GeminiException) {
            assertNotNull(e.message)
        }
    }

    @Test
    fun solverWorksOnParsedBoard() {
        // 7. satırda 3 boş hücre var: "###" parçası satırı tamamlamalı.
        val rows = List(8) { i -> if (i == 7) "#####..." else "........" }
        val read = PuzzleParser.parse(gameJson(rows)) as PuzzleRead.Game
        val plan = BlockSolver.plan(read.cells, read.pieces.map { it.shape })
        assertEquals(2, plan.placements.size)
        assertTrue(plan.lines >= 1)
    }
}
