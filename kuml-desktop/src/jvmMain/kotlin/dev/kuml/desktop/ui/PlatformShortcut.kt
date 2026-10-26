package dev.kuml.desktop.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyShortcut

/**
 * Menu shortcut in the platform's native command-key convention: ⌘ (Meta) on macOS, Ctrl on
 * Windows and Linux.
 *
 * Compose Desktop does NOT translate [KeyShortcut.ctrl] to ⌘ on macOS — `ctrl` and `meta` map
 * one-to-one to the Swing `CTRL_DOWN_MASK` / `META_DOWN_MASK` accelerator modifiers. A literal
 * `ctrl = true` therefore shows up as ⌃S on a Mac instead of the expected ⌘S. Every menu shortcut
 * in kUML Desktop goes through this function so the convention lives in one place.
 */
internal fun platformShortcut(
    key: Key,
    shift: Boolean = false,
    alt: Boolean = false,
    os: String = System.getProperty("os.name", ""),
): KeyShortcut =
    if (isMacOs(os)) {
        KeyShortcut(key = key, meta = true, alt = alt, shift = shift)
    } else {
        KeyShortcut(key = key, ctrl = true, alt = alt, shift = shift)
    }

/** True for macOS (`os.name` = "Mac OS X"); `Darwin` kernels report the same. */
internal fun isMacOs(os: String = System.getProperty("os.name", "")): Boolean = os.lowercase().let { "mac" in it || "darwin" in it }

/** Display label of the command modifier for texts that mention a shortcut: "⌘" on macOS, "Ctrl" elsewhere. */
internal fun commandKeyLabel(os: String = System.getProperty("os.name", "")): String = if (isMacOs(os)) "⌘" else "Ctrl"

/**
 * `true` for the "send event" chord in a text field: Enter (or numpad Enter) key-down with the
 * platform's command modifier, i.e. ⌘ on macOS and Ctrl elsewhere. Pure, so it is unit-testable
 * without a window; the key event itself is read in `onPreviewKeyEvent`.
 */
internal fun isSendShortcut(
    key: Key,
    isMetaPressed: Boolean,
    isCtrlPressed: Boolean,
    type: KeyEventType,
    os: String = System.getProperty("os.name", ""),
): Boolean =
    type == KeyEventType.KeyDown &&
        (key == Key.Enter || key == Key.NumPadEnter) &&
        (if (isMacOs(os)) isMetaPressed else isCtrlPressed)
