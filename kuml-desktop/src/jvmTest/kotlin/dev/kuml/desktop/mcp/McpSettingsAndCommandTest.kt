package dev.kuml.desktop.mcp

import dev.kuml.desktop.AppState
import dev.kuml.desktop.i18n.Strings
import dev.kuml.desktop.io.AppSettings
import dev.kuml.desktop.io.AppSettingsStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files

class McpSettingsAndCommandTest :
    FunSpec({
        test("claude mcp add command is exact for a fixed port and token") {
            val token = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNO_-"
            claudeMcpAddCommand(port = 51234, token = token) shouldBe
                "claude mcp add --transport http kuml-desktop http://127.0.0.1:51234/mcp " +
                "--header \"Authorization: Bearer $token\""
        }

        test("defaults: server off, port 0") {
            AppSettings.DEFAULT.mcpServerEnabled shouldBe false
            AppSettings.DEFAULT.mcpServerPort shouldBe 0
        }

        test("settings round-trip keeps the MCP fields and never contains a token") {
            val dir = Files.createTempDirectory("kuml-mcp-settings")
            try {
                val store = AppSettingsStore(dir.resolve("settings.json"))
                store.save(AppSettings(mcpServerEnabled = true, mcpServerPort = 51234))
                val loaded = store.load()
                loaded.mcpServerEnabled shouldBe true
                loaded.mcpServerPort shouldBe 51234
                val raw = Files.readString(dir.resolve("settings.json"))
                raw.lowercase() shouldNotContain "token"
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        test("an old settings file without MCP fields loads with the defaults") {
            val dir = Files.createTempDirectory("kuml-mcp-settings-old")
            try {
                val file = dir.resolve("settings.json")
                Files.writeString(file, """{"theme":"kuml","language":"de","windowWidth":900}""")
                val loaded = AppSettingsStore(file).load()
                loaded.mcpServerEnabled shouldBe false
                loaded.mcpServerPort shouldBe 0
                loaded.language shouldBe "de"
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        test("AppState takes over the MCP fields and writes them back in toSettings()") {
            val state = AppState(AppSettings(mcpServerEnabled = true, mcpServerPort = 4711))
            state.mcpServerEnabled shouldBe true
            state.mcpServerPort shouldBe 4711
            state.mcpServerEnabled = false
            state.mcpServerPort = 5000
            state.toSettings().mcpServerEnabled shouldBe false
            state.toSettings().mcpServerPort shouldBe 5000
        }

        test("new MCP strings exist in both languages and differ where they should") {
            fun values(s: Strings) =
                listOf(
                    s.menuToolsMcpServer,
                    s.mcpDialogTitle,
                    s.mcpEnabled,
                    s.mcpStatusOff,
                    s.mcpStatusRunning,
                    s.mcpStatusError,
                    s.mcpPortLabel,
                    s.mcpPortChanged,
                    s.mcpTokenLabel,
                    s.mcpCopyToken,
                    s.mcpRegenerateToken,
                    s.mcpRegenerateWarning,
                    s.mcpCopyCommand,
                    s.mcpCommandWarning,
                    s.mcpCopied,
                    s.mcpScriptReplacedNotice,
                    s.mcpStatusBarIndicator,
                    s.mcpUndo,
                    s.mcpSecurityNotice,
                )
            values(Strings.DE).all { it.isNotBlank() } shouldBe true
            values(Strings.EN).all { it.isNotBlank() } shouldBe true
            (Strings.DE.mcpEnabled != Strings.EN.mcpEnabled) shouldBe true
            (Strings.DE.mcpCopyToken != Strings.EN.mcpCopyToken) shouldBe true
            (Strings.DE.mcpCopyCommand != Strings.EN.mcpCopyCommand) shouldBe true
            (Strings.DE.mcpDialogTitle != Strings.EN.mcpDialogTitle) shouldBe true
        }

        test("format placeholders: %d for port strings, %s for the error string") {
            listOf(Strings.DE, Strings.EN).forEach { s ->
                s.mcpStatusRunning shouldContain "%d"
                s.mcpStatusBarIndicator shouldContain "%d"
                s.mcpStatusError shouldContain "%s"
                s.mcpStatusRunning.format(4711) shouldContain "4711"
            }
        }

        test("German MCP texts do not use gendered forms") {
            val de = Strings.DE
            listOf(de.mcpPortChanged, de.mcpRegenerateWarning, de.mcpCommandWarning, de.mcpSecurityNotice, de.mcpScriptReplacedNotice)
                .forEach { text -> Regex("[*:_/]innen|[*:]in\\b").containsMatchIn(text) shouldBe false }
        }
    })
