package dev.kuml.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

@OptIn(ExperimentalTestApi::class)
class IconLabelTest :
    FunSpec({
        test("every action renders a visible icon, normal and compact") {
            ButtonAction.entries.forEach { action ->
                listOf(false, true).forEach { compact ->
                    withClue("${action.name} compact=$compact") {
                        runComposeUiTest {
                            setContent {
                                Box(Modifier.testTag("t").background(Color.White)) {
                                    CompositionLocalProvider(LocalContentColor provides Color.Black) {
                                        IconLabel(action = action, text = "", compact = compact)
                                    }
                                }
                            }
                            val bmp = onNodeWithTag("t").captureToImage().asSkiaBitmap()
                            var drawn = 0
                            for (y in 0 until bmp.height) {
                                for (x in 0 until bmp.width) {
                                    if ((bmp.getColor(x, y) and 0xFF) < 0xF0) drawn++
                                }
                            }
                            drawn shouldBeGreaterThan 19
                        }
                    }
                }
            }
        }

        test("COPY with showAsDone shows the check symbol instead of the copy symbol") {
            fun render(done: Boolean): List<Int> {
                var pixels = emptyList<Int>()
                runComposeUiTest {
                    setContent {
                        MaterialTheme {
                            Button(
                                onClick = {},
                            ) { IconLabel(action = ButtonAction.COPY, text = "X", showAsDone = done) }
                        }
                    }
                    val bmp = onNodeWithText("X").captureToImage().asSkiaBitmap()
                    pixels = (0 until bmp.height).flatMap { y -> (0 until bmp.width).map { x -> bmp.getColor(x, y) } }
                }
                return pixels
            }
            (render(false) == render(true)) shouldBe false
        }

        test("a button has exactly one clickable node labelled by its text; the icon has no description") {
            runComposeUiTest {
                setContent { MaterialTheme { Button(onClick = {}) { IconLabel(action = ButtonAction.SAVE, text = "Save it") } } }
                onAllNodesWithText("Save it").fetchSemanticsNodes().size shouldBe 1
                onAllNodes(hasClickAction() and hasText("Save it")).assertCountEquals(1)
                val root = onNodeWithText("Save it", useUnmergedTree = true)
                root.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription) shouldBe null
                // The icon node (unmerged) must not carry a description either.
                onAllNodesWithText("Save it", useUnmergedTree = true).fetchSemanticsNodes().forEach {
                    it.config.getOrNull(SemanticsProperties.ContentDescription) shouldBe null
                }
            }
        }

        test("a disabled button reports not enabled") {
            runComposeUiTest {
                setContent {
                    MaterialTheme {
                        Button(
                            onClick = {},
                            enabled = false,
                        ) { IconLabel(action = ButtonAction.CONFIRM, text = "Off") }
                    }
                }
                onNodeWithText("Off").assert(isNotEnabled())
            }
        }

        test("long labels are truncated to one line, never wrapped") {
            runComposeUiTest {
                setContent {
                    MaterialTheme {
                        Box(
                            Modifier.width(120.dp),
                        ) { Button(onClick = {}) { IconLabel(action = ButtonAction.SAVE, text = "A very long label that cannot fit") } }
                    }
                }
                val node = onNodeWithText("A very long label that cannot fit").fetchSemanticsNode()
                val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                node.config
                    .getOrNull(SemanticsActions.GetTextLayoutResult)
                    ?.action
                    ?.invoke(results)
                results.first().lineCount shouldBe 1
            }
        }
    })
