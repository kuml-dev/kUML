package dev.kuml.desktop.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.filter.ThresholdFilter
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy
import dev.kuml.desktop.DESKTOP_DEFAULT_LOG_LEVEL
import dev.kuml.desktop.configureLogging
import dev.kuml.desktop.io.AppPaths
import dev.kuml.desktop.normalizeInvalidLogLevel
import dev.kuml.desktop.normalizedLogLevelOverride
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.util.Comparator

class DesktopLoggingConfigTest :
    FunSpec({

        fun classpathResource(name: String) = Thread.currentThread().contextClassLoader.getResource(name)

        fun loadIsolatedConfig(logDir: String): LoggerContext {
            val context = LoggerContext()
            context.name = "desktop-logging-config-test"
            context.putProperty("kuml.desktop.logDir", logDir)
            val configurator = JoranConfigurator()
            configurator.context = context
            configurator.doConfigure(
                classpathResource("logback-kuml-desktop.xml")
                    ?: error("logback-kuml-desktop.xml not found on the test classpath"),
            )
            return context
        }

        fun restoreProperty(
            key: String,
            value: String?,
        ) {
            if (value == null) System.clearProperty(key) else System.setProperty(key, value)
        }

        fun deleteRecursively(dir: Path) {
            if (!Files.exists(dir)) return
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }

        test("SLF4J is bound to Logback, not the NOP logger — the regression this whole change fixes") {
            // This is the machine-checkable equivalent of "SLF4J(W): No SLF4J providers
            // were found." being gone: before this change, kuml-desktop had slf4j-api on
            // its runtime classpath but no provider, so getILoggerFactory() returned
            // org.slf4j.helpers.NOPLoggerFactory and every log statement was discarded.
            LoggerFactory.getILoggerFactory()::class.qualifiedName shouldBe "ch.qos.logback.classic.LoggerContext"
        }

        test("logback-kuml-desktop.xml is found on the classpath") {
            classpathResource("logback-kuml-desktop.xml") shouldNotBe null
        }

        test("exactly one logback.xml is ever on the classpath (anti-collision guard)") {
            // kuml-mcp's logback.xml is on this module's classpath transitively via
            // kuml-cli -> kuml-ai-tools -> kuml-mcp. This module must never ship a second
            // file with that exact name.
            val resources =
                Thread
                    .currentThread()
                    .contextClassLoader
                    .getResources("logback.xml")
                    .toList()
            resources shouldHaveSize 1
        }

        test("AppPaths.logDir(baseDir) creates a 'logs' directory under the given base directory") {
            // Uses an injected temp directory rather than the real per-user default (like
            // AppPathsTest does for resolveBaseDir()) — otherwise this test would create
            // and 0700-restrict the real ~/Library/Application Support/kUML directory (or
            // its Linux/Windows equivalent) on every test run, on every machine, forever,
            // and would fail outright in a sandbox with no writable/set HOME.
            val tempDir = Files.createTempDirectory("kuml-desktop-app-paths-test-")
            try {
                val dir = AppPaths.logDir(tempDir)
                Files.isDirectory(dir) shouldBe true
                dir.fileName.toString() shouldBe "logs"
                dir.parent shouldBe tempDir

                // Best-effort POSIX hardening (0700) — mirrors PlainJsonFallbackBackend's 0600 on
                // secrets.json. No-op assertion on filesystems without POSIX permissions.
                val view = Files.getFileAttributeView(dir, PosixFileAttributeView::class.java)
                if (view != null) {
                    val perms =
                        view
                            .readAttributes()
                            .permissions()
                            .map { it.name }
                            .toSet()
                    perms shouldBe setOf("OWNER_READ", "OWNER_WRITE", "OWNER_EXECUTE")
                }
            } finally {
                deleteRecursively(tempDir)
            }
        }

        test("configureLogging(logDir) sets kuml.desktop.logDir to the given directory and selects logback-kuml-desktop.xml") {
            // Uses an injected temp directory (via the logDir parameter) rather than letting
            // configureLogging() fall back to AppPaths.logDir()'s real per-user default — same
            // hermeticity reasoning as the AppPaths.logDir(baseDir) test above.
            val originalConfigFile = System.getProperty("logback.configurationFile")
            val originalLogDir = System.getProperty("kuml.desktop.logDir")
            System.clearProperty("logback.configurationFile")
            val tempDir = Files.createTempDirectory("kuml-desktop-configure-logging-test-")
            try {
                val logDir = AppPaths.logDir(tempDir)
                configureLogging(logDir)
                System.getProperty("logback.configurationFile") shouldBe "logback-kuml-desktop.xml"
                System.getProperty("kuml.desktop.logDir") shouldBe logDir.toString()
            } finally {
                restoreProperty("logback.configurationFile", originalConfigFile)
                restoreProperty("kuml.desktop.logDir", originalLogDir)
                deleteRecursively(tempDir)
            }
        }

        test("configureLogging(logDir) never overrides an externally-supplied logback.configurationFile") {
            val originalConfigFile = System.getProperty("logback.configurationFile")
            System.setProperty("logback.configurationFile", "some-other-config.xml")
            val tempDir = Files.createTempDirectory("kuml-desktop-configure-logging-test-")
            try {
                configureLogging(AppPaths.logDir(tempDir))
                System.getProperty("logback.configurationFile") shouldBe "some-other-config.xml"
            } finally {
                deleteRecursively(tempDir)
                restoreProperty("logback.configurationFile", originalConfigFile)
            }
        }

        test("root level is INFO with a WARN-filtered stderr console appender and a rolling file appender") {
            val tempDir = Files.createTempDirectory("kuml-desktop-logging-test-")
            try {
                val context = loadIsolatedConfig(tempDir.toString())
                context.getLogger(Logger.ROOT_LOGGER_NAME).level shouldBe Level.INFO

                val stderr =
                    context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("STDERR") as? ConsoleAppender<*>
                        ?: error("STDERR appender not found")
                stderr.target shouldBe "System.err"
                val filter = stderr.copyOfAttachedFiltersList.filterIsInstance<ThresholdFilter>().singleOrNull()
                filter shouldNotBe null

                val file =
                    context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("FILE") as? RollingFileAppender<*>
                        ?: error("FILE appender not found")
                file.file shouldStartWith tempDir.toString()
                file.rollingPolicy.shouldBeInstanceOf<SizeAndTimeBasedRollingPolicy<*>>()
            } finally {
                deleteRecursively(tempDir)
            }
        }

        test("no appender in the desktop configuration ever targets System.out") {
            val tempDir = Files.createTempDirectory("kuml-desktop-logging-test-")
            try {
                val context = loadIsolatedConfig(tempDir.toString())
                context.loggerList
                    .flatMap { logger -> logger.iteratorForAppenders().asSequence().toList() }
                    .filterIsInstance<ConsoleAppender<ILoggingEvent>>()
                    .filter { it.target == "System.out" } shouldHaveSize 0
            } finally {
                deleteRecursively(tempDir)
            }
        }

        test("a warn-level event is actually written to the rolling log file") {
            val tempDir = Files.createTempDirectory("kuml-desktop-logging-test-")
            try {
                val context = loadIsolatedConfig(tempDir.toString())
                val testLogger = context.getLogger("dev.kuml.desktop.test")
                val fileAppender =
                    context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("FILE") as? RollingFileAppender<*>
                        ?: error("FILE appender not found")

                val marker = "DESKTOP-FILE-APPENDER-MARKER-${System.nanoTime()}"
                // Built manually (instead of testLogger.warn(marker)) with the MDC property
                // map pre-populated: LoggingEvent.prepareForDeferredProcessing() unconditionally
                // calls getMDCPropertyMap(), which lazily binds org.slf4j.MDC's static
                // MDCAdapter on first touch in this JVM. In a shared Gradle test JVM running
                // hundreds of unrelated specs, some earlier, unrelated test can end up
                // triggering that one-shot static binding before SLF4J's provider has fully
                // bound, permanently leaving it null for the rest of the JVM's life — a known
                // SLF4J/Logback caveat, unrelated to this module's logging configuration.
                // Pre-setting the map short-circuits that lazy lookup entirely, making this
                // test independent of what any other spec in the suite happened to do first.
                val event =
                    LoggingEvent(Logger::class.java.name, testLogger, Level.WARN, marker, null, null).apply {
                        mdcPropertyMap = emptyMap()
                    }
                @Suppress("UNCHECKED_CAST")
                (fileAppender as RollingFileAppender<ILoggingEvent>).doAppend(event)
                fileAppender.stop() // flush + close before reading the file back

                val logFile = tempDir.resolve("kuml-desktop.log")
                Files.exists(logFile) shouldBe true
                Files.readString(logFile) shouldContain marker
            } finally {
                deleteRecursively(tempDir)
            }
        }

        test("the FILE appender neutralizes embedded CR/LF in the message channel so a forged log line cannot be injected") {
            // Regression test for the %replace(%msg){'[\r\n]', '\\n'} conversion word in
            // logback-kuml-desktop.xml (see the file's header comment): third-party plugin
            // exception messages (KumlToolRegistry.kt logs e.message from foreign plugin
            // code) are not under kUML's control and could otherwise embed a CR/LF plus a
            // fake log-line prefix, splitting one logged event into what looks like several
            // independent physical log lines to anyone/anything parsing the file afterwards.
            // This test covers ONLY the message channel — see the two tests below for the
            // thread-name and exception-dump channels, which are separate conversion words
            // in the same pattern and each need their own %replace wrapping.
            val tempDir = Files.createTempDirectory("kuml-desktop-logging-test-")
            try {
                val context = loadIsolatedConfig(tempDir.toString())
                val testLogger = context.getLogger("dev.kuml.desktop.test")
                val fileAppender =
                    context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("FILE") as? RollingFileAppender<*>
                        ?: error("FILE appender not found")

                val marker = "DESKTOP-CRLF-FORGE-MARKER-${System.nanoTime()}"
                // A single logged message embedding a CRLF followed by text shaped like an
                // independent, higher-severity log line — the forgery this pattern exists to
                // neutralize.
                val forgedMessage =
                    "$marker: plugin error\r\n12:00:00.000 ERROR some.forged.Logger - fully fabricated line"

                val event =
                    LoggingEvent(Logger::class.java.name, testLogger, Level.WARN, forgedMessage, null, null).apply {
                        mdcPropertyMap = emptyMap()
                    }
                @Suppress("UNCHECKED_CAST")
                (fileAppender as RollingFileAppender<ILoggingEvent>).doAppend(event)
                fileAppender.stop() // flush + close before reading the file back

                val logFile = tempDir.resolve("kuml-desktop.log")
                Files.exists(logFile) shouldBe true
                val lines = Files.readAllLines(logFile)
                // The whole point: no matter how many CR/LFs the message embeds, it must
                // still land as exactly ONE physical line in the file.
                lines shouldHaveSize 1
                val physicalLine = lines.single()
                physicalLine shouldContain marker
                physicalLine shouldContain "fully fabricated line"
                // The CR/LF survives only as the literal two-character escape, never as a
                // real line break.
                physicalLine shouldContain "\\n"
            } finally {
                deleteRecursively(tempDir)
            }
        }

        test("the FILE appender neutralizes embedded CR/LF in the thread-name channel so a forged log line cannot be injected") {
            // Negative/tamper test for the second CRLF-forgery channel: a plugin running in
            // kuml-desktop's process (via kuml-plugin-loader) can name its own thread
            // arbitrarily, including a name shaped like a fake independent log line. Before
            // %replace(%thread){...} was added, %thread was emitted completely unescaped.
            val tempDir = Files.createTempDirectory("kuml-desktop-logging-test-")
            try {
                val context = loadIsolatedConfig(tempDir.toString())
                val testLogger = context.getLogger("dev.kuml.desktop.test")
                val fileAppender =
                    context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("FILE") as? RollingFileAppender<*>
                        ?: error("FILE appender not found")

                val marker = "DESKTOP-THREAD-FORGE-MARKER-${System.nanoTime()}"
                val forgedThreadName =
                    "$marker\r\n12:00:00.000 ERROR some.forged.Logger - fully fabricated line"

                val event =
                    LoggingEvent(Logger::class.java.name, testLogger, Level.WARN, "benign message", null, null).apply {
                        mdcPropertyMap = emptyMap()
                        threadName = forgedThreadName
                    }
                @Suppress("UNCHECKED_CAST")
                (fileAppender as RollingFileAppender<ILoggingEvent>).doAppend(event)
                fileAppender.stop() // flush + close before reading the file back

                val logFile = tempDir.resolve("kuml-desktop.log")
                Files.exists(logFile) shouldBe true
                val lines = Files.readAllLines(logFile)
                // A forged thread name must not split the event into several physical lines.
                lines shouldHaveSize 1
                val physicalLine = lines.single()
                physicalLine shouldContain marker
                physicalLine shouldContain "fully fabricated line"
                physicalLine shouldContain "\\n"
            } finally {
                deleteRecursively(tempDir)
            }
        }

        test("the FILE appender neutralizes embedded CR/LF in the exception-dump channel so a forged log line cannot be injected") {
            // Negative/tamper test for the third CRLF-forgery channel: the exception dump
            // Logback appends whenever a Throwable is logged (here explicitly via %ex,
            // wrapped in %replace — an unwrapped/implicit exception dump would reopen this
            // hole). Mirrors the real callsite in KumlAiExecutor.close(): a delegate
            // PromptExecutor is an interchangeable, third-party-implementable dependency,
            // and its exception's getMessage() is not under kUML's control.
            val tempDir = Files.createTempDirectory("kuml-desktop-logging-test-")
            try {
                val context = loadIsolatedConfig(tempDir.toString())
                val testLogger = context.getLogger("dev.kuml.desktop.test")
                val fileAppender =
                    context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("FILE") as? RollingFileAppender<*>
                        ?: error("FILE appender not found")

                val marker = "DESKTOP-EXCEPTION-FORGE-MARKER-${System.nanoTime()}"
                val forgedThrowable =
                    RuntimeException(
                        "$marker\r\n12:00:00.000 ERROR some.forged.Logger - fully fabricated line",
                    )

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
                @Suppress("UNCHECKED_CAST")
                (fileAppender as RollingFileAppender<ILoggingEvent>).doAppend(event)
                fileAppender.stop() // flush + close before reading the file back

                val logFile = tempDir.resolve("kuml-desktop.log")
                Files.exists(logFile) shouldBe true
                val lines = Files.readAllLines(logFile)
                // A forged exception message must not split the event (message line plus
                // the appended stack-trace dump) into several physical lines.
                lines shouldHaveSize 1
                val physicalLine = lines.single()
                physicalLine shouldContain marker
                physicalLine shouldContain "fully fabricated line"
                physicalLine shouldContain "\\n"
            } finally {
                deleteRecursively(tempDir)
            }
        }

        test("the FILE appender neutralizes embedded CR/LF in the logger-name channel so a forged log line cannot be injected") {
            // Negative/tamper test for the fourth CRLF-forgery channel: third-party plugin
            // code running inside kuml-desktop (via kuml-plugin-loader's parent-first
            // PluginClassLoader, which shares this process's slf4j-api/logback with the
            // host) can call LoggerFactory.getLogger(String) with an arbitrary name,
            // including one shaped like a fake independent, higher-severity log line.
            // %logger{40}'s abbreviation alone does not neutralize this — the abbreviator
            // only shortens segments before the last dot, so a name whose final segment
            // (or a dotless name) carries the CR/LF survives verbatim.
            val tempDir = Files.createTempDirectory("kuml-desktop-logging-test-")
            try {
                val context = loadIsolatedConfig(tempDir.toString())
                val marker = "DESKTOP-LOGGER-FORGE-MARKER-${System.nanoTime()}"
                // Deliberately dotless: TargetLengthBasedClassNameAbbreviator only shortens
                // dot-separated segments, so a name with no dots at all (like a plugin id, or
                // any string a plugin author chooses to pass to LoggerFactory.getLogger) is
                // never abbreviated and survives verbatim regardless of length — exactly the
                // case the header comment calls out. (A dotted name, e.g. one embedding a
                // fake "some.forged.Logger" class name, would instead get its earlier
                // segments abbreviated by the *abbreviator itself*, which incidentally also
                // destroys the CR/LF — that's a different, accidental line of defense this
                // test intentionally does not rely on.)
                val forgedLoggerName =
                    "$marker\r\nERROR FORGED-LOGGER - fully fabricated line"
                val testLogger = context.getLogger(forgedLoggerName)
                val fileAppender =
                    context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("FILE") as? RollingFileAppender<*>
                        ?: error("FILE appender not found")

                val event =
                    LoggingEvent(Logger::class.java.name, testLogger, Level.WARN, "benign message", null, null).apply {
                        mdcPropertyMap = emptyMap()
                    }
                @Suppress("UNCHECKED_CAST")
                (fileAppender as RollingFileAppender<ILoggingEvent>).doAppend(event)
                fileAppender.stop() // flush + close before reading the file back

                val logFile = tempDir.resolve("kuml-desktop.log")
                Files.exists(logFile) shouldBe true
                val lines = Files.readAllLines(logFile)
                // A forged logger name must not split the event into several physical lines.
                lines shouldHaveSize 1
                val physicalLine = lines.single()
                physicalLine shouldContain marker
                physicalLine shouldContain "fully fabricated line"
                physicalLine shouldContain "\\n"
            } finally {
                deleteRecursively(tempDir)
            }
        }

        test("the FILE appender separates %msg from a non-empty %ex, but adds no trailing separator when there is no exception") {
            // Regression test for the missing separator between %msg and %ex: before the
            // nested %replace(...){'^(?=.)', ' -- '} was added, a message and its exception
            // dump were concatenated with zero characters between them (e.g.
            // "close failedjava.lang.RuntimeException: boom"), which is both hard to read
            // and ambiguous to parse. The fix must only add the separator when %ex is
            // actually non-empty — the vast majority of log lines carry no exception at
            // all and must stay exactly as before (no dangling " -- ").
            val tempDir = Files.createTempDirectory("kuml-desktop-logging-test-")
            try {
                val context = loadIsolatedConfig(tempDir.toString())
                val testLogger = context.getLogger("dev.kuml.desktop.test")
                val fileAppender =
                    context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("FILE") as? RollingFileAppender<*>
                        ?: error("FILE appender not found")

                val plainMarker = "DESKTOP-NO-EX-MARKER-${System.nanoTime()}"
                val plainEvent =
                    LoggingEvent(Logger::class.java.name, testLogger, Level.WARN, plainMarker, null, null).apply {
                        mdcPropertyMap = emptyMap()
                    }
                @Suppress("UNCHECKED_CAST")
                (fileAppender as RollingFileAppender<ILoggingEvent>).doAppend(plainEvent)

                val exMarker = "DESKTOP-WITH-EX-MARKER-${System.nanoTime()}"
                val exEvent =
                    LoggingEvent(
                        Logger::class.java.name,
                        testLogger,
                        Level.WARN,
                        exMarker,
                        RuntimeException("boom"),
                        null,
                    ).apply {
                        mdcPropertyMap = emptyMap()
                    }
                fileAppender.doAppend(exEvent)
                fileAppender.stop() // flush + close before reading the file back

                val logFile = tempDir.resolve("kuml-desktop.log")
                val lines = Files.readAllLines(logFile)
                lines shouldHaveSize 2

                val plainLine = lines[0]
                plainLine shouldContain plainMarker
                // No exception on this event: the line must end right after the message,
                // with no dangling " -- " separator.
                plainLine shouldNotContain " -- "

                val exLine = lines[1]
                exLine shouldContain exMarker
                exLine shouldContain "java.lang.RuntimeException: boom"
                // The separator must actually be present between message and exception dump.
                exLine shouldContain "$exMarker -- java.lang.RuntimeException"
            } finally {
                deleteRecursively(tempDir)
            }
        }

        test("the FILE appender neutralizes non-CRLF Unicode line separators so a forged log line cannot be injected") {
            // Regression test for the character-class widening in the FILE pattern's four
            // %replace(...){'[\r\n...]', '\\n'} conversion words: Files.readAllLines() (used
            // by the CR/LF-only tamper tests above) splits ONLY on \n/\r/\r\n, so it would
            // stay green even if the pattern still forged a fake line via NEL (U+0085), LINE
            // SEPARATOR (U+2028) or PARAGRAPH SEPARATOR (U+2029) — all three are line breaks
            // under Java's \R regex class and under common log consumers (Splunk,
            // Loki/Promtail) and editors. This test verifies directly against the raw file
            // bytes with Pattern.compile("\\R") instead, so it would have caught that gap.
            val tempDir = Files.createTempDirectory("kuml-desktop-logging-test-")
            try {
                val context = loadIsolatedConfig(tempDir.toString())
                val testLogger = context.getLogger("dev.kuml.desktop.test")
                val fileAppender =
                    context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("FILE") as? RollingFileAppender<*>
                        ?: error("FILE appender not found")

                val marker = "DESKTOP-UNICODE-SEP-FORGE-MARKER-${System.nanoTime()}"
                // NEL, LINE SEPARATOR and PARAGRAPH SEPARATOR — none of them \r or \n.
                val forgedMessage =
                    "$marker: plugin error  12:00:00.000 ERROR some.forged.Logger - fully fabricated line"

                val event =
                    LoggingEvent(Logger::class.java.name, testLogger, Level.WARN, forgedMessage, null, null).apply {
                        mdcPropertyMap = emptyMap()
                    }
                @Suppress("UNCHECKED_CAST")
                (fileAppender as RollingFileAppender<ILoggingEvent>).doAppend(event)
                fileAppender.stop() // flush + close before reading the file back

                val logFile = tempDir.resolve("kuml-desktop.log")
                Files.exists(logFile) shouldBe true
                val content = Files.readString(logFile)
                // Split on any Unicode line-break sequence Java's regex engine recognizes,
                // not just \r/\n/\r\n — the whole point of this test.
                val physicalLines =
                    java.util.regex.Pattern
                        .compile("\\R")
                        .split(content)
                        .filter { it.isNotEmpty() }
                physicalLines shouldHaveSize 1
                val physicalLine = physicalLines.single()
                physicalLine shouldContain marker
                physicalLine shouldContain "fully fabricated line"
                // Each neutralized separator survives only as the literal two-character
                // escape, never as a real Unicode line break.
                physicalLine shouldContain "\\n\\n\\n"
            } finally {
                deleteRecursively(tempDir)
            }
        }

        test("normalizedLogLevelOverride leaves an unset or valid KUML_LOG_LEVEL alone") {
            normalizedLogLevelOverride(raw = null, default = DESKTOP_DEFAULT_LOG_LEVEL) shouldBe null
            listOf("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF", "ALL", "info", "  Debug  ")
                .forEach { valid ->
                    normalizedLogLevelOverride(raw = valid, default = DESKTOP_DEFAULT_LOG_LEVEL) shouldBe null
                }
        }

        test("normalizedLogLevelOverride replaces an invalid KUML_LOG_LEVEL with the module default") {
            // Plausible-but-wrong guesses that Level.toLevel(String) would otherwise silently
            // fall back to DEBUG for.
            listOf("SILENT", "NONE", "quiet", "verbose", "")
                .forEach { invalid ->
                    normalizedLogLevelOverride(
                        raw = invalid,
                        default = DESKTOP_DEFAULT_LOG_LEVEL,
                    ) shouldBe DESKTOP_DEFAULT_LOG_LEVEL
                }
        }

        test("normalizeInvalidLogLevel normalizes a system-property KUML_LOG_LEVEL, not just the OS environment") {
            // Regression test: normalizeInvalidLogLevel() used to read ONLY
            // System.getenv("KUML_LOG_LEVEL"), so an invalid value set exclusively via a
            // jpackage .cfg file's java-options (-DKUML_LOG_LEVEL=..., a system property
            // with no matching OS environment variable — the natural way to set this on a
            // packaged desktop app) sailed straight through to Level.toLevel(String)'s
            // silent DEBUG fallback: maximum verbosity instead of the intended quiet.
            val original = System.getProperty("KUML_LOG_LEVEL")
            System.setProperty("KUML_LOG_LEVEL", "SILENT")
            try {
                normalizeInvalidLogLevel(DESKTOP_DEFAULT_LOG_LEVEL)
                System.getProperty("KUML_LOG_LEVEL") shouldBe DESKTOP_DEFAULT_LOG_LEVEL
            } finally {
                if (original == null) System.clearProperty("KUML_LOG_LEVEL") else System.setProperty("KUML_LOG_LEVEL", original)
            }
        }
    })
