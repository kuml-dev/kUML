package dev.kuml.cli.run

import dev.kuml.runtime.snapshot.MigrationPolicy
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.net.HttpURLConnection
import java.net.URI

private val STM_SCRIPT =
    """
    stateDiagram(name = "TrafficLight") {
        val init = initialState()
        val red = state(name = "Red")
        val green = state(name = "Green")
        val off = finalState(name = "Off")
        transition(source = init, target = red)
        transition(source = red, target = green) { trigger = "timer60s" }
        transition(source = green, target = off) { trigger = "powerOff" }
    }
    """.trimIndent()

private fun makeManager(script: String = STM_SCRIPT): RunSessionManager {
    val manager = RunSessionManager()
    manager.start(
        scriptText = script,
        scriptName = "test.kuml.kts",
        restoreFrom = null,
        migrationPolicy = MigrationPolicy.AcceptIfFingerprintMatches,
    )
    return manager
}

private fun httpPost(
    port: Int,
    path: String,
    body: String,
): Pair<Int, String> {
    val conn = URI("http://localhost:$port$path").toURL().openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.doOutput = true
    conn.setRequestProperty("Content-Type", "application/json")
    val bytes = body.toByteArray()
    conn.setRequestProperty("Content-Length", bytes.size.toString())
    conn.outputStream.use { it.write(bytes) }
    val code = conn.responseCode
    val text =
        if (code < 400) {
            conn.inputStream.bufferedReader().readText()
        } else {
            conn.errorStream?.bufferedReader()?.readText() ?: ""
        }
    return code to text
}

private fun httpGet(
    port: Int,
    path: String,
): Pair<Int, String> {
    val conn = URI("http://localhost:$port$path").toURL().openConnection() as HttpURLConnection
    conn.requestMethod = "GET"
    val code = conn.responseCode
    val text =
        if (code < 400) {
            conn.inputStream.bufferedReader().readText()
        } else {
            conn.errorStream?.bufferedReader()?.readText() ?: ""
        }
    return code to text
}

class McpHttpAdapterTest :
    FunSpec({

        // ── 1. POST /run/event returns active states ───────────────────────────

        test("POST /run/event returns active states") {
            val manager = makeManager()
            val adapter = McpHttpAdapter(manager, 0)
            val port = adapter.start()
            try {
                val (code, body) = httpPost(port, "/run/event", """{"name":"timer60s"}""")
                code shouldBe 200
                body shouldContain "activeStates"
                body shouldContain "fired"
            } finally {
                adapter.stop()
            }
        }

        // ── 2. GET /run/snapshot returns state info ───────────────────────────

        test("GET /run/snapshot returns state info") {
            val manager = makeManager()
            val adapter = McpHttpAdapter(manager, 0)
            val port = adapter.start()
            try {
                val (code, body) = httpGet(port, "/run/snapshot")
                code shouldBe 200
                body shouldContain "activeStates"
            } finally {
                adapter.stop()
            }
        }

        // ── 3. POST /run/patch updates variables ──────────────────────────────

        test("POST /run/patch updates variables") {
            val manager = makeManager()
            val adapter = McpHttpAdapter(manager, 0)
            val port = adapter.start()
            try {
                val (code, body) = httpPost(port, "/run/patch", """{"variables":{"counter":1}}""")
                code shouldBe 200
                body shouldContain "ok"
            } finally {
                adapter.stop()
            }
        }

        // ── 4. GET /run/health returns ok ─────────────────────────────────────

        test("GET /run/health returns ok") {
            val manager = makeManager()
            val adapter = McpHttpAdapter(manager, 0)
            val port = adapter.start()
            try {
                val (code, body) = httpGet(port, "/run/health")
                code shouldBe 200
                body shouldContain "ok"
                body shouldContain "version"
            } finally {
                adapter.stop()
            }
        }

        // ── 5. POST /run/stop terminates server ───────────────────────────────

        test("POST /run/stop terminates server") {
            val manager = makeManager()
            val adapter = McpHttpAdapter(manager, 0)
            val port = adapter.start()
            val (code, body) = httpPost(port, "/run/stop", "")
            code shouldBe 200
            body shouldContain "totalSteps"
            // Wait briefly for the server to shut down
            Thread.sleep(300)
        }

        // ── 6. Port 0 binds random free port ─────────────────────────────────

        test("port 0 binds random free port") {
            val manager = makeManager()
            val adapter = McpHttpAdapter(manager, 0)
            val port = adapter.start()
            try {
                port shouldBeGreaterThan 0
            } finally {
                adapter.stop()
            }
        }
    })
