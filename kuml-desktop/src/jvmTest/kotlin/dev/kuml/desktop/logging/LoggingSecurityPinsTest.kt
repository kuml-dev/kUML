package dev.kuml.desktop.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/**
 * Freezes the security-critical invariant that raising `KUML_LOG_LEVEL` can
 * never turn on request/response body logging for third-party HTTP/AI stacks.
 * Ktor, Koog, AWS Smithy, the AWS SDK, and Skiko log verbosely at DEBUG/TRACE —
 * Ktor/Koog/Smithy/AWS SDK specifically include `Authorization: Bearer sk-...`
 * headers. See kuml-desktop/src/jvmMain/resources/logback-kuml-desktop.xml.
 */
class LoggingSecurityPinsTest :
    FunSpec({

        fun loadConfig(): LoggerContext {
            val tempDir = Files.createTempDirectory("kuml-desktop-security-pins-test-")
            val context = LoggerContext()
            context.name = "desktop-logging-security-pins-test"
            context.putProperty("kuml.desktop.logDir", tempDir.toString())
            val resource =
                Thread.currentThread().contextClassLoader.getResource("logback-kuml-desktop.xml")
                    ?: error("logback-kuml-desktop.xml not found on the test classpath")
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
            context.getLogger("org.jetbrains.skiko").level shouldBe Level.WARN
            context.getLogger("org.apache.hc").level shouldBe Level.WARN
            context.getLogger("io.netty").level shouldBe Level.WARN
        }

        test("KUML_LOG_LEVEL=DEBUG raises the root logger but never the pinned third-party loggers") {
            System.setProperty("KUML_LOG_LEVEL", "DEBUG")
            try {
                val context = loadConfig()
                context.getLogger(Logger.ROOT_LOGGER_NAME).level shouldBe Level.DEBUG

                context.getLogger("io.ktor.client.HttpClient").effectiveLevel shouldBe Level.WARN
                context.getLogger("ai.koog.prompt.executor.SomeExecutor").effectiveLevel shouldBe Level.WARN
                // Regression: the koog-gonka provider (de.betchvaia.koog.gonka.GonkaLLMClient)
                // logs the full prompt at debug via an injected KLogger, and does NOT fall
                // under the ai.koog.* pin above because it lives in a different package.
                context.getLogger("de.betchvaia.koog.gonka.GonkaLLMClient").effectiveLevel shouldBe Level.WARN
                context.getLogger("aws.smithy.kotlin.runtime.http.SomeClient").effectiveLevel shouldBe Level.WARN
                context.getLogger("software.amazon.awssdk.services.SomeClient").effectiveLevel shouldBe Level.WARN
                context.getLogger("org.eclipse.elk.core.SomeLayoutProvider").effectiveLevel shouldBe Level.WARN
                context.getLogger("org.jetbrains.skiko.SomeRenderer").effectiveLevel shouldBe Level.WARN
                // Regression: Ktor's default HttpClient() constructor picks an engine via
                // ServiceLoader with no deterministic ordering guarantee — if
                // ktor-client-apache5 wins over ktor-client-cio, Apache HttpClient 5 logs
                // Authorization headers and full bodies at DEBUG via org.apache.hc.*, and
                // that traffic persists to the rolling FILE appender (7-day retention).
                context.getLogger("org.apache.hc.client5.http.wire").effectiveLevel shouldBe Level.WARN
                context.getLogger("org.apache.hc.client5.http.headers").effectiveLevel shouldBe Level.WARN
                context.getLogger("io.netty.handler.codec.http.SomeCodec").effectiveLevel shouldBe Level.WARN
            } finally {
                System.clearProperty("KUML_LOG_LEVEL")
            }
        }
    })
