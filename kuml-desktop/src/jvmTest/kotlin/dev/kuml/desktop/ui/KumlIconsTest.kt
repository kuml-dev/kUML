package dev.kuml.desktop.ui

import androidx.compose.ui.graphics.vector.ImageVector
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class KumlIconsTest :
    FunSpec({

        val allIcons: List<Pair<String, ImageVector>> =
            listOf(
                "ZoomIn" to KumlIcons.ZoomIn,
                "ZoomOut" to KumlIcons.ZoomOut,
                "FitToWindow" to KumlIcons.FitToWindow,
                "ViewSource" to KumlIcons.ViewSource,
                "ViewSplit" to KumlIcons.ViewSplit,
                "ViewDiagram" to KumlIcons.ViewDiagram,
            )

        test("all six icons use a 24x24 viewport") {
            allIcons.forEach { (name, icon) ->
                withClue(name) {
                    icon.viewportWidth shouldBe 24f
                    icon.viewportHeight shouldBe 24f
                }
            }
        }

        test("all six icons contain at least one drawn path (not an empty vector)") {
            allIcons.forEach { (name, icon) ->
                withClue(name) {
                    (icon.root.size > 0) shouldBe true
                }
            }
        }

        test("ZoomIn and ZoomOut are distinct vectors") {
            KumlIcons.ZoomIn shouldNotBe KumlIcons.ZoomOut
        }

        test("ViewSource and ViewDiagram are distinct vectors (mirrored, not identical)") {
            KumlIcons.ViewSource shouldNotBe KumlIcons.ViewDiagram
        }

        test("repeated access returns the same cached instance (lazy val, not rebuilt per call)") {
            (KumlIcons.ZoomIn === KumlIcons.ZoomIn) shouldBe true
        }
    })
