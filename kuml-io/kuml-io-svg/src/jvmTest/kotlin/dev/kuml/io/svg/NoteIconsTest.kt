@file:Suppress("RequireNamedArguments")

package dev.kuml.io.svg

import dev.kuml.core.model.note.NoteKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

/** The symbols are rasterised on a 16 x 16 mask (even-odd, polygon paths) so their silhouettes can be compared. */
class NoteIconsTest :
    FunSpec(body = {
        val kinds = NoteKind.entries.filter { it.hasIcon }
        val grid = 16
        val samples = 4

        fun polygons(d: String): List<List<Pair<Double, Double>>> =
            d.split("M").map { it.trim() }.filter { it.isNotEmpty() }.map { sub ->
                val nums =
                    sub
                        .replace("L", " ")
                        .replace("Z", " ")
                        .trim()
                        .split(Regex("\\s+"))
                        .map { it.toDouble() }
                nums.chunked(2).map { it[0] to it[1] }
            }

        fun inside(
            poly: List<Pair<Double, Double>>,
            x: Double,
            y: Double,
        ): Boolean {
            var c = false
            var j = poly.size - 1
            for (i in poly.indices) {
                val (xi, yi) = poly[i]
                val (xj, yj) = poly[j]
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c
                j = i
            }
            return c
        }

        fun mask(kind: NoteKind): BooleanArray {
            val polys = polygons(NoteIcons.path(kind))
            return BooleanArray(grid * grid) { idx ->
                val cx = idx % grid
                val cy = idx / grid
                var filled = 0
                for (sy in 0 until samples) {
                    for (sx in 0 until samples) {
                        val x = (cx + (sx + 0.5) / samples) * 24.0 / grid
                        val y = (cy + (sy + 0.5) / samples) * 24.0 / grid
                        // even-odd: a point is filled when it lies inside an odd number of sub-paths
                        if (polys.count { inside(it, x, y) } % 2 == 1) filled++
                    }
                }
                filled * 2 >= samples * samples
            }
        }

        val masks = kinds.associateWith { mask(it) }

        test("there are 14 symbols and NOTE has none") {
            kinds.size shouldBe 14
            NoteIcons.path(NoteKind.NOTE) shouldBe ""
        }

        test("every symbol fills between 15 and 85 percent of its box") {
            for (k in kinds) {
                val share = masks.getValue(k).count { it }.toDouble() / (grid * grid)
                (share >= 0.15 && share <= 0.85) shouldBe true
            }
        }

        test("every pair of symbols differs in at least 12 percent of the pixels") {
            for (i in kinds.indices) {
                for (j in i + 1 until kinds.size) {
                    val a = masks.getValue(kinds[i])
                    val b = masks.getValue(kinds[j])
                    val diff = a.indices.count { a[it] != b[it] }.toDouble() / (grid * grid)
                    withClue("${kinds[i]} vs ${kinds[j]}") { diff shouldBeGreaterThanOrEqualTo 0.12 }
                }
            }
        }

        test("paths stay on the 24 grid and carry no markup or user data") {
            for (k in kinds) {
                val d = NoteIcons.path(k)
                d shouldNotContain "<"
                d shouldNotContain "\""
                polygons(d).flatten().forEach { (x, y) ->
                    (x in 0.0..24.0 && y in 0.0..24.0) shouldBe true
                }
                NoteIcons.label(k).isNotBlank() shouldBe true
            }
        }
    })

private inline fun <T> withClue(
    clue: String,
    block: () -> T,
): T = io.kotest.assertions.withClue(clue, block)
