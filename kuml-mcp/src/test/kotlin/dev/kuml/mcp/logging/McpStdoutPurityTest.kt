package dev.kuml.mcp.logging

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.Appender
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.status.NopStatusListener
import dev.kuml.mcp.JsonRpcResponse
import dev.kuml.mcp.McpServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets

/**
 * Regression tests for the hard rule that stdout is kuml-mcp's JSON-RPC protocol
 * channel: not a single byte of log output, or of Logback's own status output,
 * may ever reach it. See kuml-mcp/src/main/resources/logback.xml for the
 * rationale.
 */
class McpStdoutPurityTest :
    FunSpec({

        fun classpathLogbackXml() =
            Thread.currentThread().contextClassLoader.getResource("logback.xml")
                ?: error("logback.xml not found on the test classpath")

        /** Loads the real logback.xml into a fresh, isolated context — never touches the shared one. */
        fun loadIsolatedConfig(): LoggerContext {
            val context = LoggerContext()
            context.name = "mcp-stdout-purity-test"
            val configurator = JoranConfigurator()
            configurator.context = context
            configurator.doConfigure(classpathLogbackXml())
            return context
        }

        fun allAppenders(context: LoggerContext): List<Appender<ILoggingEvent>> =
            context.loggerList.flatMap { logger -> logger.iteratorForAppenders().asSequence().toList() }

        test("logback.xml is found on the classpath") {
            classpathLogbackXml() shouldNotBe null
        }

        test("exactly one appender is attached to root, a ConsoleAppender targeting System.err") {
            val context = loadIsolatedConfig()
            val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
            val rootAppenders = root.iteratorForAppenders().asSequence().toList()
            rootAppenders shouldHaveSize 1
            val appender = rootAppenders.single()
            appender.shouldBeInstanceOf<ConsoleAppender<ILoggingEvent>>()
            appender.target shouldBe "System.err"
        }

        test("no appender anywhere in the context ever targets System.out") {
            val context = loadIsolatedConfig()
            val offendingAppenders =
                allAppenders(context)
                    .filterIsInstance<ConsoleAppender<ILoggingEvent>>()
                    .filter { it.target == "System.out" }
            offendingAppenders shouldHaveSize 0
        }

        test("a NopStatusListener is registered so Logback's own status output never reaches stdout") {
            val context = loadIsolatedConfig()
            context.statusManager.copyOfStatusListenerList.any { it is NopStatusListener } shouldBe true
        }

        test("end-to-end: stdout carries only clean JSON-RPC responses, log output goes to stderr") {
            val origIn = System.`in`
            val origOut = System.out
            val origErr = System.err
            val capturedOut = ByteArrayOutputStream()
            val capturedErr = ByteArrayOutputStream()
            // The shared, statically-bound LoggerContext: its ConsoleAppender captured
            // System.out/err references whenever it first started (possibly before this
            // test, from an earlier test in this module). Swapping System.out/err alone is
            // therefore not enough — the context must be reset and reconfigured AFTER the
            // swap so the appender re-captures the streams this test controls, then
            // restored afterwards for any tests that run later in the same JVM.
            val sharedContext = LoggerFactory.getILoggerFactory() as LoggerContext

            fun reconfigure() {
                sharedContext.reset()
                JoranConfigurator().apply { context = sharedContext }.doConfigure(classpathLogbackXml())
            }

            try {
                val input =
                    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""" + "\n" +
                        """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""" + "\n"
                System.setIn(ByteArrayInputStream(input.toByteArray(StandardCharsets.UTF_8)))
                System.setOut(PrintStream(capturedOut, true, StandardCharsets.UTF_8))
                System.setErr(PrintStream(capturedErr, true, StandardCharsets.UTF_8))
                reconfigure()

                LoggerFactory.getLogger("dev.kuml.mcp.test").error("MARKER-SHOULD-NEVER-REACH-STDOUT")

                McpServer.run()
            } finally {
                System.setIn(origIn)
                System.setOut(origOut)
                System.setErr(origErr)
                reconfigure()
            }

            val outText = capturedOut.toString(StandardCharsets.UTF_8)
            val errText = capturedErr.toString(StandardCharsets.UTF_8)

            outText shouldNotContain "MARKER-SHOULD-NEVER-REACH-STDOUT"
            errText shouldContain "MARKER-SHOULD-NEVER-REACH-STDOUT"

            val outLines = outText.lines().filter { it.isNotBlank() }
            outLines shouldHaveSize 2
            outLines.forEach { line ->
                val response = McpServer.json.decodeFromString(JsonRpcResponse.serializer(), line)
                response.jsonrpc shouldBe "2.0"
            }
        }
    })
