package dev.kuml.workspace.pdf

import dev.kuml.workspace.pdf.layout.PageGeometry
import dev.kuml.workspace.pdf.layout.PagePlan
import dev.kuml.workspace.pdf.layout.PagePlanner
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class PagePlannerTest :
    FunSpec({
        val design = PageGeometry(marginA4 = 70.87f, marginA3 = 70.87f)
        val prod = PageGeometry()

        fun plan(
            w: Float,
            h: Float,
            g: PageGeometry,
            base: PageFormat = PageFormat.A4,
            orientation: PageOrientation = PageOrientation.AUTO,
            strategy: PageStrategy = PageStrategy.AUTO,
            tiling: Boolean = true,
            minScale: Float = 0.5f,
        ): PagePlan =
            PagePlanner.plan(
                w = w,
                h = h,
                base = base,
                orientation = orientation,
                strategy = strategy,
                tiling = tiling,
                minScale = minScale,
                g = g,
            )

        context("design vectors (70.87 pt margin everywhere)") {
            test("400x300 stays A4 portrait at full scale") {
                val p = plan(400f, 300f, design)
                p.format shouldBe PageFormat.A4
                p.landscape shouldBe false
                p.scale shouldBe 1f
                p.tiles shouldBe 1
            }
            test("1200x800 turns landscape through refinement R1") {
                val p = plan(1200f, 800f, design)
                p.format shouldBe PageFormat.A4
                p.landscape shouldBe true
                p.scale shouldBe (0.522f plusOrMinus 0.005f)
            }
            test("1800x1200 moves to A3 landscape") {
                val p = plan(1800f, 1200f, design)
                p.format shouldBe PageFormat.A3
                p.landscape shouldBe true
                p.scale shouldBe (0.553f plusOrMinus 0.005f)
            }
            test("4000x2500 is tiled on A4 portrait 5x2") {
                val p = plan(4000f, 2500f, design)
                p.format shouldBe PageFormat.A4
                p.landscape shouldBe false
                p.cols to p.rows shouldBe (5 to 2)
                p.scale shouldBe (0.520f plusOrMinus 0.005f)
            }
            test("4000x2500 without tiling is illegible on A3 landscape") {
                val p = plan(4000f, 2500f, design, tiling = false)
                p.illegible shouldBe true
                p.format shouldBe PageFormat.A3
                p.landscape shouldBe true
                p.scale shouldBe (0.262f plusOrMinus 0.005f)
            }
            test("FIXED keeps the A4 format and tiles instead of using A3") {
                val p = plan(1800f, 1200f, design, orientation = PageOrientation.PORTRAIT, strategy = PageStrategy.FIXED)
                p.format shouldBe PageFormat.A4
                p.landscape shouldBe false
                p.cols to p.rows shouldBe (3 to 1)
            }
            test("20000x12000 is capped at 24 tiles and illegible") {
                val p = plan(20000f, 12000f, design)
                (p.tiles <= 24) shouldBe true
                p.illegible shouldBe true
            }
        }

        context("production margins") {
            test("1200x800 A4 landscape") {
                val p = plan(1200f, 800f, prod)
                p.landscape shouldBe true
                p.scale shouldBe (0.557f plusOrMinus 0.005f)
            }
            test("4000x2500 is 3x3 on A4 landscape") {
                val p = plan(4000f, 2500f, prod)
                p.format shouldBe PageFormat.A4
                p.landscape shouldBe true
                p.cols to p.rows shouldBe (3 to 3)
                p.scale shouldBe (0.512f plusOrMinus 0.005f)
            }
            test("1800x1200 FIXED portrait is 2x1") {
                val p = plan(1800f, 1200f, prod, orientation = PageOrientation.PORTRAIT, strategy = PageStrategy.FIXED)
                p.landscape shouldBe false
                p.cols to p.rows shouldBe (2 to 1)
                p.scale shouldBe (0.520f plusOrMinus 0.01f)
            }
        }

        context("FIXED keeps the previous behaviour") {
            test("explicit orientations are respected and not tiled when readable") {
                plan(1200f, 800f, prod, orientation = PageOrientation.PORTRAIT, strategy = PageStrategy.FIXED, minScale = 0.35f).let {
                    it.landscape shouldBe false
                    it.tiles shouldBe 1
                }
                plan(400f, 300f, prod, orientation = PageOrientation.LANDSCAPE, strategy = PageStrategy.FIXED).landscape shouldBe true
            }
            test("AUTO orientation in FIXED uses the 0.15 rule without R1") {
                // find a size with sP < 0.5 <= sL and sL - sP < 0.15
                var found: Pair<Float, Float>? = null
                outer@ for (w in 800..2000 step 20) {
                    for (h in 400..1500 step 20) {
                        val sP = PagePlanner.singleScale(w = w.toFloat(), h = h.toFloat(), f = PageFormat.A4, landscape = false, g = prod)
                        val sL = PagePlanner.singleScale(w = w.toFloat(), h = h.toFloat(), f = PageFormat.A4, landscape = true, g = prod)
                        if (sP in 0.35f..0.499f && sL >= 0.5f && sL - sP < 0.15f) {
                            found = w.toFloat() to h.toFloat()
                            break@outer
                        }
                    }
                }
                found shouldNotBe null
                val (w, h) = found!!
                val fixed = plan(w, h, prod, strategy = PageStrategy.FIXED, tiling = false)
                fixed.landscape shouldBe false
                fixed.illegible shouldBe true
                plan(w, h, prod, strategy = PageStrategy.AUTO).landscape shouldBe true
            }
        }

        test("A3 base format skips the A3 stage and tiles on A3") {
            val p = plan(6000f, 4000f, prod, base = PageFormat.A3)
            p.format shouldBe PageFormat.A3
            (p.tiles > 1) shouldBe true
        }

        test("degenerate sizes do not fail") {
            plan(0f, -1f, prod).scale shouldBe 1f
            plan(Float.NaN, Float.POSITIVE_INFINITY, prod).scale shouldBe 1f
        }

        test("properties over a size grid") {
            val sizes = listOf(50f, 200f, 600f, 1200f, 2500f, 5000f, 12000f, 30000f)
            for (w in sizes) {
                for (h in sizes) {
                    for (strategy in PageStrategy.entries) {
                        for (tiling in listOf(true, false)) {
                            for (ms in listOf(0.25f, 0.5f, 0.8f)) {
                                for (g in listOf(prod, design)) {
                                    val p =
                                        plan(
                                            w,
                                            h,
                                            g,
                                            strategy = strategy,
                                            tiling = tiling,
                                            minScale = ms,
                                            orientation = PageOrientation.AUTO,
                                        )
                                    withClue("w=$w h=$h $strategy tiling=$tiling ms=$ms -> $p") {
                                        if (!p.illegible) (p.scale >= ms - 1e-4f) shouldBe true
                                        (p.tiles <= 24) shouldBe true
                                        (p.scale <= 1f) shouldBe true
                                        if (!tiling) p.tiles shouldBe 1
                                        if (p.tiles > 1) {
                                            val cw = g.contentW(f = p.format, landscape = p.landscape)
                                            val ch = g.contentH(f = p.format, landscape = p.landscape)
                                            ((p.cols - 1) * (cw - g.overlap) < w * p.scale) shouldBe true
                                            ((p.rows - 1) * (ch - g.overlap) < h * p.scale) shouldBe true
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    })
