package dev.kuml.cli.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.core.Appender
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.status.Status
import dev.kuml.cli.CLI_DEFAULT_LOG_LEVEL
import dev.kuml.cli.configureLogging
import dev.kuml.cli.normalizeInvalidLogLevel
import dev.kuml.cli.normalizedLogLevelOverride
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * Tests for kuml-cli's Logback configuration (`logback-kuml-cli.xml`) and the
 * `configureLogging()` selection logic in Main.kt.
 *
 * stdout is the CLI's DATA channel (`kuml render --format svg > x.svg`,
 * structured JSON errors) — every assertion here exists to prevent a single
 * byte of log output from ever landing there.
 */
class CliLoggingConfigTest :
    FunSpec({

        fun classpathResource(name: String) = Thread.currentThread().contextClassLoader.getResource(name)

        fun loadIsolatedConfig(): LoggerContext {
            val context = LoggerContext()
            context.name = "cli-logging-config-test"
            val configurator = JoranConfigurator()
            configurator.context = context
            configurator.doConfigure(
                classpathResource("logback-kuml-cli.xml") ?: error("logback-kuml-cli.xml not found on the test classpath"),
            )
            return context
        }

        fun allAppenders(context: LoggerContext): List<Appender<ILoggingEvent>> =
            context.loggerList.flatMap { logger -> logger.iteratorForAppenders().asSequence().toList() }

        test("logback-kuml-cli.xml is found on the classpath") {
            classpathResource("logback-kuml-cli.xml") shouldNotBe null
        }

        test("at most one logback.xml is ever on the classpath (anti-collision guard)") {
            // Guards against the module-graph pitfall: kuml-mcp <- kuml-ai-tools <- kuml-cli.
            // If a second file named `logback.xml` were ever added anywhere on this
            // classpath, Logback's classpath-order-dependent lookup would become
            // non-deterministic. See gradle/libs.versions.toml and
            // kuml-mcp/src/main/resources/logback.xml for the full rationale.
            //
            // Asserts "<= 1", not "== 1": kuml-mcp's logback.xml only reaches this
            // classpath transitively through kuml-ai-tools, and that dependency sits
            // behind the `aiEnabled` build flag (see kuml-cli/build.gradle.kts). Under
            // `-Pkuml.noAi=true` there are legitimately zero copies — kuml-cli falls
            // back to logback-kuml-cli.xml alone in that mode — and "zero" must pass
            // this guard just as well as "one". What must never happen is "two or
            // more", which is the actual collision this test exists to catch.
            val resources =
                Thread
                    .currentThread()
                    .contextClassLoader
                    .getResources("logback.xml")
                    .toList()
            resources.size shouldBeLessThanOrEqual 1
        }

        test("the configuration parses without any ERROR-level Joran status") {
            val context = loadIsolatedConfig()
            val errors = context.statusManager.copyOfStatusList.filter { it.level == Status.ERROR }
            errors shouldHaveSize 0
        }

        test("root level is WARN and the only appender is a ConsoleAppender targeting System.err") {
            val context = loadIsolatedConfig()
            context.getLogger(Logger.ROOT_LOGGER_NAME).level shouldBe Level.WARN
            val rootAppenders =
                context
                    .getLogger(Logger.ROOT_LOGGER_NAME)
                    .iteratorForAppenders()
                    .asSequence()
                    .toList()
            rootAppenders shouldHaveSize 1
            val appender = rootAppenders.single()
            appender.shouldBeInstanceOf<ConsoleAppender<ILoggingEvent>>()
            appender.target shouldBe "System.err"
        }

        test("no appender anywhere in the configuration ever targets System.out") {
            val context = loadIsolatedConfig()
            allAppenders(context)
                .filterIsInstance<ConsoleAppender<ILoggingEvent>>()
                .filter { it.target == "System.out" } shouldHaveSize 0
        }

        test("third-party HTTP/AI loggers are pinned to WARN regardless of KUML_LOG_LEVEL") {
            System.setProperty("KUML_LOG_LEVEL", "DEBUG")
            try {
                val context = loadIsolatedConfig()
                context.getLogger(Logger.ROOT_LOGGER_NAME).level shouldBe Level.DEBUG
                context.getLogger("io.ktor.client.HttpClient").effectiveLevel shouldBe Level.WARN
                context.getLogger("ai.koog.SomeClient").effectiveLevel shouldBe Level.WARN
                // Regression: the koog-gonka provider (de.betchvaia.koog.gonka.GonkaLLMClient)
                // logs the full prompt at debug via an injected KLogger, and does NOT fall
                // under the ai.koog.* pin above because it lives in a different package.
                context.getLogger("de.betchvaia.koog.gonka.GonkaLLMClient").effectiveLevel shouldBe Level.WARN
                context.getLogger("aws.smithy.kotlin.SomeClient").effectiveLevel shouldBe Level.WARN
                context.getLogger("software.amazon.awssdk.SomeClient").effectiveLevel shouldBe Level.WARN
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

        test("the STDERR appender neutralizes embedded CR/LF in the message channel so a forged log line cannot be injected") {
            // Regression test for the %replace(%msg){...} conversion word added to
            // logback-kuml-cli.xml's STDERR pattern: kuml-cli loads third-party plugins via
            // kuml-plugin-loader (parent-first PluginClassLoader sharing this process's
            // slf4j-api/logback with foreign code), and KumlToolRegistry.kt logs e.message
            // from foreign plugin exceptions — a message embedding CR/LF plus text shaped
            // like an independent, higher-severity log line could otherwise masquerade as
            // several fabricated physical lines to anything parsing piped/redirected stderr
            // (CI logs, `2> debug.log`).
            val originalErr = System.err
            val buffer = ByteArrayOutputStream()
            System.setErr(PrintStream(buffer, true, "UTF-8"))
            try {
                val context = loadIsolatedConfig()
                val testLogger = context.getLogger("dev.kuml.cli.test")
                val stderrAppender =
                    context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("STDERR")
                        ?: error("STDERR appender not found")

                val marker = "CLI-CRLF-FORGE-MARKER-${System.nanoTime()}"
                val forgedMessage =
                    "$marker: plugin error\r\n12:00:00.000 ERROR some.forged.Logger - fully fabricated line"
                val event =
                    LoggingEvent(Logger::class.java.name, testLogger, Level.WARN, forgedMessage, null, null).apply {
                        mdcPropertyMap = emptyMap()
                    }
                stderrAppender.doAppend(event)

                val output = buffer.toString("UTF-8")
                val lines = output.lines().filter { it.isNotEmpty() }
                // The whole point: no matter how many CR/LFs the message embeds, it must
                // still land as exactly ONE physical line on stderr.
                lines shouldHaveSize 1
                val physicalLine = lines.single()
                physicalLine shouldContain marker
                physicalLine shouldContain "fully fabricated line"
                physicalLine shouldContain "\\n"
            } finally {
                System.setErr(originalErr)
            }
        }

        test("the STDERR appender neutralizes embedded CR/LF in the thread-name channel so a forged log line cannot be injected") {
            // Negative/tamper test for the second CRLF-forgery channel: a plugin running in
            // kuml-cli's process (via kuml-plugin-loader) can name its own thread arbitrarily.
            val originalErr = System.err
            val buffer = ByteArrayOutputStream()
            System.setErr(PrintStream(buffer, true, "UTF-8"))
            try {
                val context = loadIsolatedConfig()
                val testLogger = context.getLogger("dev.kuml.cli.test")
                val stderrAppender =
                    context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("STDERR")
                        ?: error("STDERR appender not found")

                val marker = "CLI-THREAD-FORGE-MARKER-${System.nanoTime()}"
                val forgedThreadName =
                    "$marker\r\n12:00:00.000 ERROR some.forged.Logger - fully fabricated line"
                val event =
                    LoggingEvent(Logger::class.java.name, testLogger, Level.WARN, "benign message", null, null).apply {
                        mdcPropertyMap = emptyMap()
                        threadName = forgedThreadName
                    }
                stderrAppender.doAppend(event)

                val output = buffer.toString("UTF-8")
                val lines = output.lines().filter { it.isNotEmpty() }
                lines shouldHaveSize 1
                val physicalLine = lines.single()
                physicalLine shouldContain marker
                physicalLine shouldContain "fully fabricated line"
                physicalLine shouldContain "\\n"
            } finally {
                System.setErr(originalErr)
            }
        }

        test("the STDERR appender neutralizes embedded CR/LF in the logger-name channel so a forged log line cannot be injected") {
            // Negative/tamper test for the third CRLF-forgery channel: third-party plugin
            // code can call LoggerFactory.getLogger(String) with an arbitrary, dotless name
            // that %logger{36}'s abbreviator would not touch.
            val originalErr = System.err
            val buffer = ByteArrayOutputStream()
            System.setErr(PrintStream(buffer, true, "UTF-8"))
            try {
                val context = loadIsolatedConfig()
                val marker = "CLI-LOGGER-FORGE-MARKER-${System.nanoTime()}"
                val forgedLoggerName = "$marker\r\nERROR FORGED-LOGGER - fully fabricated line"
                val testLogger = context.getLogger(forgedLoggerName)
                val stderrAppender =
                    context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("STDERR")
                        ?: error("STDERR appender not found")

                val event =
                    LoggingEvent(Logger::class.java.name, testLogger, Level.WARN, "benign message", null, null).apply {
                        mdcPropertyMap = emptyMap()
                    }
                stderrAppender.doAppend(event)

                val output = buffer.toString("UTF-8")
                val lines = output.lines().filter { it.isNotEmpty() }
                lines shouldHaveSize 1
                val physicalLine = lines.single()
                physicalLine shouldContain marker
                physicalLine shouldContain "fully fabricated line"
                physicalLine shouldContain "\\n"
            } finally {
                System.setErr(originalErr)
            }
        }

        test("the STDERR appender neutralizes embedded CR/LF in the exception-dump channel so a forged log line cannot be injected") {
            // Negative/tamper test for the fourth CRLF-forgery channel: the exception dump
            // appended via the explicit %ex conversion word. A delegate PromptExecutor is an
            // interchangeable, third-party-implementable dependency and its exception's
            // getMessage() is not under kUML's control.
            val originalErr = System.err
            val buffer = ByteArrayOutputStream()
            System.setErr(PrintStream(buffer, true, "UTF-8"))
            try {
                val context = loadIsolatedConfig()
                val testLogger = context.getLogger("dev.kuml.cli.test")
                val stderrAppender =
                    context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("STDERR")
                        ?: error("STDERR appender not found")

                val marker = "CLI-EXCEPTION-FORGE-MARKER-${System.nanoTime()}"
                val forgedThrowable =
                    RuntimeException("$marker\r\n12:00:00.000 ERROR some.forged.Logger - fully fabricated line")
                val event =
                    LoggingEvent(
                        Logger::class.java.name,
                        testLogger,
                        Level.WARN,
                        "benign message",
                        forgedThrowable,
                        null,
                    ).apply {
                        mdcPropertyMap = emptyMap()
                    }
                stderrAppender.doAppend(event)

                val output = buffer.toString("UTF-8")
                val lines = output.lines().filter { it.isNotEmpty() }
                lines shouldHaveSize 1
                val physicalLine = lines.single()
                physicalLine shouldContain marker
                physicalLine shouldContain "fully fabricated line"
                physicalLine shouldContain "\\n"
            } finally {
                System.setErr(originalErr)
            }
        }

        test("configureLogging() selects logback-kuml-cli.xml when nothing was set before") {
            val original = System.getProperty("logback.configurationFile")
            System.clearProperty("logback.configurationFile")
            try {
                configureLogging()
                System.getProperty("logback.configurationFile") shouldBe "logback-kuml-cli.xml"
            } finally {
                if (original ==
                    null
                ) {
                    System.clearProperty("logback.configurationFile")
                } else {
                    System.setProperty("logback.configurationFile", original)
                }
            }
        }

        test("configureLogging() never overrides an externally-supplied logback.configurationFile") {
            val original = System.getProperty("logback.configurationFile")
            System.setProperty("logback.configurationFile", "some-other-config.xml")
            try {
                configureLogging()
                System.getProperty("logback.configurationFile") shouldBe "some-other-config.xml"
            } finally {
                if (original ==
                    null
                ) {
                    System.clearProperty("logback.configurationFile")
                } else {
                    System.setProperty("logback.configurationFile", original)
                }
            }
        }

        test("normalizedLogLevelOverride leaves an unset KUML_LOG_LEVEL alone") {
            // Logback's own ${KUML_LOG_LEVEL:-WARN} substitution already handles this case.
            normalizedLogLevelOverride(raw = null, default = CLI_DEFAULT_LOG_LEVEL) shouldBe null
        }

        test("normalizedLogLevelOverride leaves every level Logback actually recognizes alone") {
            listOf("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF", "ALL", "debug", "  Warn  ")
                .forEach { valid ->
                    normalizedLogLevelOverride(raw = valid, default = CLI_DEFAULT_LOG_LEVEL) shouldBe null
                }
        }

        test("normalizedLogLevelOverride replaces an invalid value with the module default") {
            // These are exactly the kind of plausible-but-wrong guesses a user reaching for
            // "quieter than WARN" might type — none of them are real Level names, so
            // Level.toLevel(String) would otherwise silently fall back to DEBUG.
            listOf("SILENT", "NONE", "quiet", "verbose", "")
                .forEach { invalid ->
                    normalizedLogLevelOverride(raw = invalid, default = CLI_DEFAULT_LOG_LEVEL) shouldBe CLI_DEFAULT_LOG_LEVEL
                }
        }

        test("an invalid KUML_LOG_LEVEL does not fall back to DEBUG once normalized") {
            // End-to-end regression for the actual bug: apply the normalized override the
            // same way normalizeInvalidLogLevel() would, then load the real XML and check
            // the resulting root level is the documented default, not DEBUG.
            val original = System.getProperty("KUML_LOG_LEVEL")
            val override = normalizedLogLevelOverride(raw = "SILENT", default = CLI_DEFAULT_LOG_LEVEL)
            System.setProperty("KUML_LOG_LEVEL", override ?: error("expected an override for an invalid value"))
            try {
                val context = loadIsolatedConfig()
                context.getLogger(Logger.ROOT_LOGGER_NAME).level shouldBe Level.WARN
            } finally {
                if (original == null) System.clearProperty("KUML_LOG_LEVEL") else System.setProperty("KUML_LOG_LEVEL", original)
            }
        }

        test("normalizeInvalidLogLevel normalizes a system-property KUML_LOG_LEVEL, not just the OS environment") {
            // Regression test: normalizeInvalidLogLevel() used to read ONLY
            // System.getenv("KUML_LOG_LEVEL"), so an invalid value set exclusively via
            // -DKUML_LOG_LEVEL=... (a system property, with no matching OS environment
            // variable — the only way to set it on some launchers, and the exact channel
            // KUML_LOG_LEVEL is documented as pre-empting via Logback's own lookup order)
            // sailed straight through to Level.toLevel(String)'s silent DEBUG fallback.
            val original = System.getProperty("KUML_LOG_LEVEL")
            System.setProperty("KUML_LOG_LEVEL", "SILENT")
            try {
                normalizeInvalidLogLevel(CLI_DEFAULT_LOG_LEVEL)
                System.getProperty("KUML_LOG_LEVEL") shouldBe CLI_DEFAULT_LOG_LEVEL
            } finally {
                if (original == null) System.clearProperty("KUML_LOG_LEVEL") else System.setProperty("KUML_LOG_LEVEL", original)
            }
        }
    })
