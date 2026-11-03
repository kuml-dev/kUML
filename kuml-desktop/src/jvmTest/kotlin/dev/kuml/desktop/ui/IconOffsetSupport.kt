package dev.kuml.desktop.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag

/**
 * How far the label text of the button tagged [buttonTag] sits right of centre, in pixels.
 *
 * A button centres its content row. Without an icon the text is centred as well, so the left and the
 * right gap between text and button edge are equal and the offset is 0. An icon in front of the text
 * pushes the text to the right by the icon width plus its spacing, so the offset is positive. This
 * detects an icon without pixel comparison, and independent of font, button padding and minimum width.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.iconOffset(
    buttonTag: String,
    text: String,
): Float {
    val button = onNodeWithTag(buttonTag).fetchSemanticsNode().boundsInRoot
    val label = onNode(hasText(text) and hasAnyAncestor(hasTestTag(buttonTag)), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    return (label.left - button.left) - (button.right - label.right)
}
