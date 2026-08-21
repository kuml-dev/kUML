package dev.kuml.mcp.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import dev.kuml.mcp.MCP_DEFAULT_LOG_LEVEL
import dev.kuml.mcp.normalizeInvalidLogLevel
import dev.kuml.mcp.normalizedLogLevelOverride
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Freezes the security-critical invariant that raising `KUML_LOG_LEVEL` can
 * never turn on request/response body logging for third-party HTTP/AI stacks.
 * Ktor, Koog, AWS Smithy and the AWS SDK log full requests — including
 * `Authorization: Bearer sk-...` headers — at DEBUG/TRACE. See
 * kuml-mcp/src/main/resources/logback.xml for the full rationale.
 */
class LoggingSecurityPinsTest :
    FunSpec({

        fun loadConfig(): LoggerContext {
            val context = LoggerContext()
            context.name = "mcp-logging-security-pins-test"
            val resource =
                Thread.currentThread().contextClassLoader.getResource("logback.xml")
                    ?: error("logback.xml not found on the test classpath")
            JoranConfigurator().apply { this.context = context }.doConfigure(resource)
            return context
        }

        test("third-party loggers are pinned to WARN by default") {
            val context = loadConfig()
            context.getLogger("io.ktor").level shouldBe Level.WARN
            context.getLogger("ai.koog").level shouldBe Level.WARN
            context.getLogger("de.betchvaia").level shouldBe Level.WARN
            context.getLogger("aws.smithy.kotlin").level shouldBe Level.WARN
            context.getLogger("software.amazon.awssdk").level shouldBe Level.WARN
            context.getLogger("org.eclipse.elk").level shouldBe Level.WARN
            context.getLogger("org.apache.hc").level shouldBe Level.WARN
            context.getLogger("io.netty").level shouldBe Level.WARN
        }

        test("KUML_LOG_LEVEL=DEBUG raises the root logger but never the pinned third-party loggers") {
            System.setProperty("KUML_LOG_LEVEL", "DEBUG")
            try {
                val context = loadConfig()
                context.getLogger(Logger.ROOT_LOGGER_NAME).level shouldBe Level.DEBUG

                // Effective level (inherited through the pin), not the root's DEBUG.
                context.getLogger("io.ktor.client.HttpClient").effectiveLevel shouldBe Level.WARN
                context.getLogger("ai.koog.prompt.executor.SomeExecutor").effectiveLevel shouldBe Level.WARN
                // Regression: the koog-gonka provider (de.betchvaia.koog.gonka.GonkaLLMClient)
                // logs the full prompt at debug via an injected KLogger, and does NOT fall
                // under the ai.koog.* pin above because it lives in a different package.
                context.getLogger("de.betchvaia.koog.gonka.GonkaLLMClient").effectiveLevel shouldBe Level.WARN
                context.getLogger("aws.smithy.kotlin.runtime.http.SomeClient").effectiveLevel shouldBe Level.WARN
                context.getLogger("software.amazon.awssdk.services.SomeClient").effectiveLevel shouldBe Level.WARN
                context.getLogger("org.eclipse.elk.core.SomeLayoutProvider").effectiveLevel shouldBe Level.WARN
                // Regression: Ktor's default HttpClient() constructor picks an engine via
                // ServiceLoader with no deterministic ordering guarantee — if
                // ktor-client-apache5 wins over ktor-client-cio, Apache HttpClient 5 logs
                // Authorization headers and full bodies at DEBUG via org.apache.hc.*.
                context.getLogger("org.apache.hc.client5.http.wire").effectiveLevel shouldBe Level.WARN
                context.getLogger("org.apache.hc.client5.http.headers").effectiveLevel shouldBe Level.WARN
                context.getLogger("io.netty.handler.codec.http.SomeCodec").effectiveLevel shouldBe Level.WARN
            } finally {
                System.clearProperty("KUML_LOG_LEVEL")
            }
        }

        test("normalizedLogLevelOverride leaves an unset or valid KUML_LOG_LEVEL alone") {
            normalizedLogLevelOverride(raw = null, default = MCP_DEFAULT_LOG_LEVEL) shouldBe null
            listOf("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF", "ALL", "warn", "  Off  ")
                .forEach { valid ->
                    normalizedLogLevelOverride(raw = valid, default = MCP_DEFAULT_LOG_LEVEL) shouldBe null
                }
        }

        test("normalizedLogLevelOverride replaces an invalid KUML_LOG_LEVEL with the module default") {
            // Plausible-but-wrong guesses that Level.toLevel(String) would otherwise silently
            // fall back to DEBUG for — the opposite of the "quieter" a user setting these
            // probably wanted.
            listOf("SILENT", "NONE", "quiet", "verbose", "")
                .forEach { invalid ->
                    normalizedLogLevelOverride(
                        raw = invalid,
                        default = MCP_DEFAULT_LOG_LEVEL,
                    ) shouldBe MCP_DEFAULT_LOG_LEVEL
                }
        }

        test("normalizeInvalidLogLevel normalizes a system-property KUML_LOG_LEVEL, not just the OS environment") {
            // Regression test: normalizeInvalidLogLevel() used to read ONLY
            // System.getenv("KUML_LOG_LEVEL"), so an invalid value set exclusively via
            // -DKUML_LOG_LEVEL=... (a system property, with no matching OS environment
            // variable) sailed straight through to Level.toLevel(String)'s silent DEBUG
            // fallback, defeating the whole point of this guard.
            val original = System.getProperty("KUML_LOG_LEVEL")
            System.setProperty("KUML_LOG_LEVEL", "SILENT")
            try {
                normalizeInvalidLogLevel(MCP_DEFAULT_LOG_LEVEL)
                System.getProperty("KUML_LOG_LEVEL") shouldBe MCP_DEFAULT_LOG_LEVEL
            } finally {
                if (original == null) System.clearProperty("KUML_LOG_LEVEL") else System.setProperty("KUML_LOG_LEVEL", original)
            }
        }
    })
