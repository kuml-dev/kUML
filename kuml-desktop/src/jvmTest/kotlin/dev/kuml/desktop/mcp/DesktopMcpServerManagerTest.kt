package dev.kuml.desktop.mcp

import dev.kuml.desktop.AppState
import dev.kuml.mcp.McpRequestHandler
import dev.kuml.mcp.McpServerInfo
import dev.kuml.mcp.tools.McpToolRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files

class DesktopMcpServerManagerTest :
    FunSpec({
        fun manager(
            state: AppState,
            dir: java.nio.file.Path,
        ) = DesktopMcpServerManager(
            state = state,
            tokenStore = McpTokenStore(file = dir.resolve("mcp-token")),
            handlerFactory = {
                McpRequestHandler(
                    registry = McpToolRegistry(tools = emptyList()),
                    serverInfo = McpServerInfo(name = "t", version = "1"),
                )
            },
        )

        fun awaitStatus(
            m: DesktopMcpServerManager,
            predicate: (McpServerStatus) -> Boolean,
        ): McpServerStatus {
            val deadline = System.currentTimeMillis() + 10_000
            while (!predicate(m.status.value) && System.currentTimeMillis() < deadline) Thread.sleep(25)
            return m.status.value
        }

        test("apply(true) starts the server, remembers the port and exposes a token; apply(false) stops it") {
            val dir = Files.createTempDirectory("kuml-mcp-mgr")
            val state = AppState()
            val m = manager(state, dir)
            try {
                m.apply(enabled = true)
                val running = awaitStatus(m) { it is McpServerStatus.Running }
                running.shouldBeInstanceOf<McpServerStatus.Running>()
                state.mcpServerPort shouldBe running.port
                m.currentToken() shouldNotBe null
                m.apply(enabled = false)
                awaitStatus(m) { it == McpServerStatus.Off } shouldBe McpServerStatus.Off
                m.currentToken() shouldBe null
            } finally {
                m.stop()
                dir.toFile().deleteRecursively()
            }
        }

        test("a squatted remembered port moves the server and rotates the token") {
            val dir = Files.createTempDirectory("kuml-mcp-mgr-squat")
            val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
            val state = AppState()
            val m = manager(state, dir)
            try {
                ServerSocket(0, 1, loopback).use { squatter ->
                    McpTokenStore(file = dir.resolve("mcp-token")).loadOrCreate().let { stored ->
                        state.mcpServerPort = squatter.localPort
                        m.apply(enabled = true)
                        val running = awaitStatus(m) { it is McpServerStatus.Running } as McpServerStatus.Running
                        running.portChanged shouldBe true
                        (running.port != squatter.localPort) shouldBe true
                        (m.currentToken() != stored) shouldBe true
                    }
                }
            } finally {
                m.stop()
                dir.toFile().deleteRecursively()
            }
        }

        test("regenerateToken invalidates the old token for the running server") {
            val dir = Files.createTempDirectory("kuml-mcp-mgr-regen")
            val m = manager(AppState(), dir)
            try {
                m.apply(enabled = true)
                awaitStatus(m) { it is McpServerStatus.Running }
                val before = m.currentToken()
                m.regenerateToken()
                val deadline = System.currentTimeMillis() + 10_000
                while (m.currentToken() == before && System.currentTimeMillis() < deadline) Thread.sleep(25)
                (m.currentToken() != before) shouldBe true
            } finally {
                m.stop()
                dir.toFile().deleteRecursively()
            }
        }

        test("rapid apply(true), apply(false), stop() leaves no server and no thread behind") {
            val dir = Files.createTempDirectory("kuml-mcp-mgr-race")
            val m = manager(AppState(), dir)
            try {
                repeat(20) {
                    m.apply(enabled = true)
                    m.apply(enabled = false)
                }
                m.apply(enabled = true)
                m.stop()
                Thread.sleep(500)
                m.status.value shouldBe McpServerStatus.Off
                m.currentToken() shouldBe null
                val lingering = Thread.getAllStackTraces().keys.filter { it.name.startsWith("kuml-mcp-http") && it.isAlive }
                lingering shouldBe emptyList()
            } finally {
                m.stop()
                dir.toFile().deleteRecursively()
            }
        }

        test("on/off flip ends stopped") {
            val dir = Files.createTempDirectory("kuml-mcp-mgr-flip")
            val m = manager(AppState(), dir)
            try {
                m.apply(enabled = true)
                m.apply(enabled = false)
                // Server start is slower on Windows than the old fixed 500 ms: poll until the queued
                // start->stop sequence has drained, then re-check that the final state holds.
                val deadline = System.nanoTime() + 10_000_000_000L
                Thread.sleep(500)
                while (m.status.value != McpServerStatus.Off && System.nanoTime() < deadline) Thread.sleep(50)
                Thread.sleep(500)
                m.status.value shouldBe McpServerStatus.Off
                m.currentToken() shouldBe null
            } finally {
                m.stop()
                dir.toFile().deleteRecursively()
            }
        }

        test("regenerate failure while the port changes stops the server and closes the port") {
            val dir = Files.createTempDirectory("kuml-mcp-mgr-failstart")
            val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
            val file = dir.resolve("mcp-token")
            McpTokenStore(file = file).loadOrCreate()
            val failing =
                object : McpTokenStore(file = file) {
                    override fun regenerate(): String = throw java.io.IOException("disk full")
                }
            val state = AppState()
            val m =
                DesktopMcpServerManager(
                    state = state,
                    tokenStore = failing,
                    handlerFactory = {
                        McpRequestHandler(
                            registry = McpToolRegistry(tools = emptyList()),
                            serverInfo = McpServerInfo(name = "t", version = "1"),
                        )
                    },
                )
            try {
                ServerSocket(0, 1, loopback).use { squatter ->
                    state.mcpServerPort = squatter.localPort
                    m.apply(enabled = true)
                    awaitStatus(m) { it is McpServerStatus.Error }.shouldBeInstanceOf<McpServerStatus.Error>()
                    m.currentToken() shouldBe null
                    val ports =
                        Thread
                            .getAllStackTraces()
                            .keys
                            .filter { it.name.startsWith("kuml-mcp-http") && it.isAlive }
                    // Worker threads are created lazily; the listening socket is what matters: no
                    // thread of the aborted server may survive.
                    ports shouldBe emptyList()
                }
            } finally {
                m.stop()
                dir.toFile().deleteRecursively()
            }
        }

        test("regenerateToken failure fails closed: server stopped, status Error") {
            val dir = Files.createTempDirectory("kuml-mcp-mgr-failregen")
            val file = dir.resolve("mcp-token")
            var fail = false
            val store =
                object : McpTokenStore(file = file) {
                    override fun regenerate(): String = if (fail) throw java.io.IOException("locked") else super.regenerate()
                }
            val m =
                DesktopMcpServerManager(
                    state = AppState(),
                    tokenStore = store,
                    handlerFactory = {
                        McpRequestHandler(
                            registry = McpToolRegistry(tools = emptyList()),
                            serverInfo = McpServerInfo(name = "t", version = "1"),
                        )
                    },
                )
            try {
                m.apply(enabled = true)
                awaitStatus(m) { it is McpServerStatus.Running }
                fail = true
                m.regenerateToken()
                awaitStatus(m) { it is McpServerStatus.Error }.shouldBeInstanceOf<McpServerStatus.Error>()
                m.currentToken() shouldBe null
            } finally {
                m.stop()
                dir.toFile().deleteRecursively()
            }
        }
    })
